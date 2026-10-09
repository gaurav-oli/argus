package com.argus.portfolio;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.email.EmailSendException;
import com.argus.email.EmailSender;
import com.argus.marketdata.MarketClock;
import com.argus.push.PushService;
import com.argus.recommendation.PositionRules;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationAction;
import com.argus.recommendation.RecommendationRepository;
import com.argus.recommendation.SignalDirection;
import com.argus.recommendation.StopLoss;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.technical.LivePriceService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches each person's REAL holdings the way the paper investor watches its own trades — but Argus cannot place
 * orders at their brokers (no API), so it protects in the two ways it can:
 *
 * <ol>
 *   <li><b>A recommended protective stop per holding</b> (same chart rule as a paper trade, measured from the price
 *       when protection began, trailing up as the stock gains, never loosened) for the person to set as a standing
 *       stop order at their broker — which then sells automatically, 24/7, without anyone responding.</li>
 *   <li><b>Alerts that escalate and then decide.</b> When a stop breaks, Agent 5 turns to AVOID, or Agent 11 flags the
 *       thesis at risk, the person gets a push; at {@link #REMIND_AFTER} a reminder; at {@link #DECIDE_AFTER} with no
 *       response Argus makes the call itself (SELL or TIGHTEN), records it as its own decision, and sends a CRITICAL
 *       push and an email saying exactly what to do at the broker. A week later the decision is scored (what the
 *       price did after it), so these calls are judged like everything else.</li>
 * </ol>
 *
 * Runs every five minutes across the US extended session (04:00-20:00 ET); escalation runs every minute, always.
 */
@Component
public class HoldingGuard {

	private static final Logger log = LoggerFactory.getLogger(HoldingGuard.class);
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	public static final Duration REMIND_AFTER = Duration.ofMinutes(5);
	public static final Duration DECIDE_AFTER = Duration.ofMinutes(15);
	/** A tighter stop is only worth telling the person about when it moves this much (fewer broker edits). */
	static final double MEANINGFUL_STOP_MOVE = 0.01;
	/** An AVOID call this confident is a SELL; below it, a TIGHTEN. */
	static final int SELL_CONVICTION = 60;
	static final Duration OUTCOME_AFTER = Duration.ofDays(7);

	private final JdbcTemplate jdbc;
	private final LivePriceService prices;
	private final ChartStudyService charts;
	private final EarningsQuietPeriodService quietPeriod;
	private final RecommendationRepository recommendations;
	private final DeepAnalysisService deepAnalyses;
	private final PushService push;
	private final EmailSender email;
	private final MarketClock clock;

	/** Off in tests so no guard pass or escalation runs against the shared test database. */
	@Value("${argus.holding-guard.enabled:true}")
	private boolean enabled = true;

	public HoldingGuard(JdbcTemplate jdbc, LivePriceService prices, ChartStudyService charts,
			EarningsQuietPeriodService quietPeriod, RecommendationRepository recommendations, DeepAnalysisService deepAnalyses,
			PushService push, EmailSender email, MarketClock clock) {
		this.jdbc = jdbc;
		this.prices = prices;
		this.charts = charts;
		this.quietPeriod = quietPeriod;
		this.recommendations = recommendations;
		this.deepAnalyses = deepAnalyses;
		this.push = push;
		this.email = email;
		this.clock = clock;
	}

	/** One real holding: a person's position in a ticker within one account (shares summed across lots). */
	record Holding(long userId, String ticker, String account, BigDecimal shares) {
	}

	// ---- watching ----

	@Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
	public void scheduledScan() {
		Instant now = Instant.now();
		if (enabled && clock.isExtendedHours(now)) {
			scan(now);
		}
	}

	void scan(Instant now) {
		List<Holding> holdings = jdbc.query("""
				select p.user_id, upper(p.ticker), coalesce(p.account, ''), sum(p.shares)
				from positions p join app_user u on u.id = p.user_id
				where u.revoked_at is null and u.guard_enabled and p.shares > 0
				group by p.user_id, upper(p.ticker), coalesce(p.account, '')""",
				(rs, n) -> new Holding(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)));
		for (Holding h : holdings) {
			try {
				guard(h, now);
			}
			catch (RuntimeException ex) {
				log.warn("Holding guard: {} for user {} failed: {}", h.ticker(), h.userId(), ex.toString());
			}
		}
	}

	void guard(Holding h, Instant now) {
		BigDecimal live = prices.latestPrice(h.ticker()).orElse(null);
		if (live == null || live.signum() <= 0) {
			return;
		}
		double price = live.doubleValue();
		ChartStudy chart = charts.studyFor(h.ticker(), price).orElse(null);
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select stop_price, start_price, high_water, initial_stop from holding_guard where user_id = ? and ticker = ? and account = ?",
				h.userId(), h.ticker(), h.account());
		if (rows.isEmpty()) {
			// Protection begins now: the stop is measured from today's price, like the paper investor's backfill.
			BigDecimal stop = StopLoss.stopFor(SignalDirection.BULLISH, chart, price);
			jdbc.update("insert into holding_guard (user_id, ticker, account, stop_price, initial_stop, start_price, high_water) "
					+ "values (?, ?, ?, ?, ?, ?, ?)", h.userId(), h.ticker(), h.account(), stop, stop, live, live);
			return;
		}
		Map<String, Object> g = rows.get(0);
		double stop = ((BigDecimal) g.get("stop_price")).doubleValue();
		double start = ((BigDecimal) g.get("start_price")).doubleValue();
		double highWater = ((BigDecimal) g.get("high_water")).doubleValue();
		boolean trailed = ((BigDecimal) g.get("initial_stop")).compareTo((BigDecimal) g.get("stop_price")) != 0;
		boolean earningsSoon = quietPeriod.statusFor(h.ticker()).status() == QuietPeriodStatus.Status.QUIET;

		PositionRules.Decision d = PositionRules.evaluate(
				new PositionRules.Position(true, start, stop, highWater, null, false, trailed, Instant.EPOCH),
				new PositionRules.Market(price, chart == null ? null : chart.atrPct(), chart == null ? null : chart.support(),
						chart == null ? null : chart.resistance(), earningsSoon),
				now);
		jdbc.update("update holding_guard set high_water = ? where user_id = ? and ticker = ? and account = ?",
				money(d.highWater()), h.userId(), h.ticker(), h.account());
		String day = now.atZone(NEW_YORK).toLocalDate().toString();

		if (d.action() == PositionRules.Action.EXIT) {
			raise(h, "STOP_BROKEN", "SELL", String.format(Locale.ROOT,
					"%s at %.2f has fallen through its protective stop of %.2f.", h.ticker(), price, stop),
					price, stop, "STOP_BROKEN:" + h.ticker() + ":" + money(stop), now);
		}
		else if (d.stop() != null && d.stop() > stop * (1 + MEANINGFUL_STOP_MOVE)) {
			jdbc.update("update holding_guard set stop_price = ?, stop_changed_at = ? where user_id = ? and ticker = ? and account = ?",
					money(d.stop()), java.sql.Timestamp.from(now), h.userId(), h.ticker(), h.account());
			raise(h, "STOP_RAISED", "TIGHTEN", String.format(Locale.ROOT,
					"%s is up to %.2f — raise its stop from %.2f to %.2f%s to protect the gain.", h.ticker(), price, stop, d.stop(),
					earningsSoon ? " (earnings ahead)" : ""), price, d.stop(),
					"STOP_RAISED:" + h.ticker() + ":" + day, now); // told at most once a day per holding
		}

		// Agent 5's own view of the stock: an AVOID on something the person holds. One alert per run of AVOID calls —
		// keyed by the call that started the run, not the latest one: Agent 5 re-reviews a stock many times a day, and
		// keying on each new call re-alerted (and re-decided, and re-emailed) the same unchanged opinion (NKE, 2026-10-09).
		recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc(h.ticker())
				.filter(r -> r.getCreatedAt().isAfter(now.minus(Duration.ofDays(1))))
				.filter(r -> r.getAction() == RecommendationAction.AVOID || r.getAction() == RecommendationAction.STRONG_AVOID)
				.ifPresent(r -> raise(h, "CALL_REVERSED", conviction(r) >= SELL_CONVICTION ? "SELL" : "TIGHTEN",
						String.format(Locale.ROOT, "Agent 5 now calls %s %s (conviction %d)%s.", h.ticker(), r.getAction().label(),
								conviction(r), r.getThesis() == null ? "" : ": " + r.getThesis()),
						price, stop, "CALL:" + h.ticker() + ":" + avoidRunStart(h.ticker(), r.getId()), now));

		// Agent 11's thesis tracker: the deep analysis behind the stock has been undermined — once per flagged analysis.
		deepAnalyses.viewFor(h.ticker()).filter(v -> v.atRisk())
				.ifPresent(v -> raise(h, "THESIS_AT_RISK", "TIGHTEN",
						h.ticker() + "'s deep-analysis thesis is at risk" + (v.atRiskReason() == null ? "." : ": " + v.atRiskReason()),
						price, stop, "THESIS:" + h.ticker() + ":"
								+ deepAnalyses.latestDone(h.ticker()).map(a -> String.valueOf(a.getId())).orElse(day), now));
	}

	/**
	 * The id of the call that began the ticker's current unbroken run of AVOID / STRONG_AVOID calls (WATCH or a buy in
	 * between ends a run) — so a repeated AVOID is one alert, and only a fresh reversal raises another.
	 */
	long avoidRunStart(String ticker, long latestId) {
		Long start = jdbc.query("select min(r.id) from recommendations r where r.ticker = ? and r.id <= ? "
				+ "and r.action in ('AVOID', 'STRONG_AVOID') and r.created_at > coalesce((select max(p.created_at) "
				+ "from recommendations p where p.ticker = ? and p.id <= ? "
				+ "and (p.action is null or p.action not in ('AVOID', 'STRONG_AVOID'))), '-infinity'::timestamptz)",
				rs -> rs.next() ? rs.getObject(1, Long.class) : null, ticker, latestId, ticker, latestId);
		return start == null ? latestId : start;
	}

	private static int conviction(Recommendation r) {
		return r.getConvictionScore() == null ? 0 : r.getConvictionScore();
	}

	/**
	 * Record and push an alert — one per STOCK (every account holding it is listed on the one alert; AMZN held in five
	 * accounts used to alert five times), once per dedupe key, and only while no other alert of its kind is open for it.
	 * A SELL says Argus decides in 15 minutes; a TIGHTEN is informational — one push, settled quietly.
	 */
	void raise(Holding h, String kind, String recommendation, String detail, double price, Double stop, String key, Instant now) {
		Integer open = jdbc.queryForObject("select count(*) from guard_alert where user_id = ? and ticker = ? and kind = ? "
				+ "and status = 'OPEN'", Integer.class, h.userId(), h.ticker(), kind);
		if (open != null && open > 0) {
			return;
		}
		String accounts = String.join(", ", jdbc.queryForList("select account from holding_guard where user_id = ? and ticker = ? "
				+ "and account <> '' order by account", String.class, h.userId(), h.ticker()));
		int inserted = jdbc.update("""
				insert into guard_alert (user_id, ticker, account, kind, recommendation, detail, price, stop_price, dedupe_key, created_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (user_id, dedupe_key) do nothing""",
				h.userId(), h.ticker(), accounts.isEmpty() ? h.account() : accounts, kind, recommendation, detail, money(price),
				stop == null ? null : money(stop), key, java.sql.Timestamp.from(now));
		if (inserted == 0) {
			return;
		}
		log.info("Holding guard: {} {} for user {} — recommends {}", kind, h.ticker(), h.userId(), recommendation);
		notifyPush(h.userId(), title(recommendation, h.ticker()), detail + ("SELL".equals(recommendation)
				? " Tap to decide — Argus decides in 15 minutes if you don't." : " Update it at your broker when you can."));
	}

	// ---- escalation: remind, then decide ----

	@Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
	public void scheduledEscalation() {
		if (enabled) {
			escalate(Instant.now());
		}
	}

	void escalate(Instant now) {
		for (Map<String, Object> a : jdbc.queryForList("select * from guard_alert where status = 'OPEN' order by created_at")) {
			long id = ((Number) a.get("id")).longValue();
			long userId = ((Number) a.get("user_id")).longValue();
			Instant created = ((java.sql.Timestamp) a.get("created_at")).toInstant();
			int level = ((Number) a.get("escalation_level")).intValue();
			String ticker = (String) a.get("ticker");
			String recommendation = (String) a.get("recommendation");
			Duration age = Duration.between(created, now);
			if (age.compareTo(DECIDE_AFTER) >= 0) {
				decideForThem(id, userId, ticker, recommendation, (String) a.get("account"), (String) a.get("detail"),
						(BigDecimal) a.get("stop_price"), now);
			}
			else if (age.compareTo(REMIND_AFTER) >= 0 && level < 1 && "SELL".equals(recommendation)) {
				jdbc.update("update guard_alert set escalation_level = 1 where id = ? and status = 'OPEN'", id);
				notifyPush(userId, "Reminder: " + title(recommendation, ticker),
						a.get("detail") + " Argus decides in 10 minutes if you don't.");
			}
		}
	}

	private void decideForThem(long id, long userId, String ticker, String recommendation, String account, String detail,
			BigDecimal stop, Instant now) {
		BigDecimal price = prices.latestPrice(ticker).orElse(null);
		int updated = jdbc.update("""
				update guard_alert set status = 'DECIDED', decided_by = 'ARGUS', decision = ?, decided_at = ?,
				  escalation_level = 2, price_at_decision = ? where id = ? and status = 'OPEN'""",
				recommendation, java.sql.Timestamp.from(now), price, id);
		if (updated == 0) {
			return; // the person answered in the meantime
		}
		if (!"SELL".equals(recommendation)) {
			// A TIGHTEN is informational: recorded quietly — the new stop is on the Protection panel; no CRITICAL push or email.
			log.info("Holding guard: no response in {} min — recorded TIGHTEN on {} quietly", DECIDE_AFTER.toMinutes(), ticker);
			return;
		}
		String where = account == null || account.isBlank() ? "" : " in " + account;
		String action = "Argus decided: SELL " + ticker + where + ". Sell it at your broker now, or let your standing stop order do it.";
		log.info("Holding guard: no response in {} min — {}", DECIDE_AFTER.toMinutes(), action);
		notifyPush(userId, "🛡 " + action, detail);
		notifyEmail(userId, action, detail);
	}

	/** The person's own answer (from the Portfolio page): records it and closes the alert. */
	public boolean decideByUser(long userId, long alertId, String decision) {
		BigDecimal price = jdbc.query("select ticker from guard_alert where id = ? and user_id = ?",
				rs -> rs.next() ? prices.latestPrice(rs.getString(1)).orElse(null) : null, alertId, userId);
		return jdbc.update("""
				update guard_alert set status = 'DECIDED', decided_by = 'USER', decision = ?, decided_at = now(),
				  price_at_decision = ? where id = ? and user_id = ? and status = 'OPEN'""",
				decision, price, alertId, userId) == 1;
	}

	/** Turn protection on or off for a person. Off also closes their open alerts, so Argus decides nothing for them. */
	public void setEnabled(long userId, boolean on) {
		jdbc.update("update app_user set guard_enabled = ? where id = ?", on, userId);
		if (!on) {
			jdbc.update("update guard_alert set status = 'DECIDED', decided_by = 'USER', decision = 'HOLD', decided_at = now() "
					+ "where user_id = ? and status = 'OPEN'", userId);
		}
	}

	public boolean isEnabled(long userId) {
		return Boolean.TRUE.equals(jdbc.query("select guard_enabled from app_user where id = ?",
				rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, userId));
	}

	/** The person confirms they set the recommended stop as a standing order at their broker. */
	public boolean confirmBrokerStop(long userId, String ticker, String account) {
		return jdbc.update("update holding_guard set broker_stop = stop_price, broker_confirmed_at = now() "
				+ "where user_id = ? and ticker = ? and account = ?", userId, ticker.toUpperCase(Locale.ROOT),
				account == null ? "" : account) == 1;
	}

	// ---- judging the decisions ----

	@Scheduled(cron = "0 15 * * * *")
	public void scheduledOutcomes() {
		if (enabled) {
			recordOutcomes(Instant.now());
		}
	}

	/** A week after each decision: what the price did since, so SELL/TIGHTEN calls (by Argus or the person) are scored. */
	void recordOutcomes(Instant now) {
		for (Map<String, Object> a : jdbc.queryForList("select id, ticker, price_at_decision from guard_alert where status = 'DECIDED' "
				+ "and outcome_pct is null and price_at_decision is not null and decided_at < ?",
				java.sql.Timestamp.from(now.minus(OUTCOME_AFTER)))) {
			BigDecimal before = (BigDecimal) a.get("price_at_decision");
			prices.latestPrice((String) a.get("ticker")).filter(p -> p.signum() > 0 && before.signum() > 0).ifPresent(after ->
					jdbc.update("update guard_alert set price_after = ?, outcome_pct = ? where id = ?", after,
							after.subtract(before).divide(before, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)),
							((Number) a.get("id")).longValue()));
		}
	}

	// ---- delivery ----

	private static String title(String recommendation, String ticker) {
		return "SELL".equals(recommendation) ? "⚠ Consider selling " + ticker : "🛡 Tighten your stop on " + ticker;
	}

	private void notifyPush(long userId, String title, String body) {
		try {
			push.sendToUser(userId, title, body, "/portfolio");
		}
		catch (RuntimeException ex) {
			log.warn("Holding guard push to user {} failed: {}", userId, ex.getMessage());
		}
	}

	private void notifyEmail(long userId, String subject, String body) {
		String to = jdbc.query("select email from app_user where id = ?", rs -> rs.next() ? rs.getString(1) : null, userId);
		if (to == null || to.isBlank() || !email.configured()) {
			return;
		}
		try {
			email.send(to, "Argus — " + subject,
					"<div style=\"font-family:Arial,sans-serif;font-size:15px;color:#111\"><p><strong>" + escape(subject)
							+ "</strong></p><p>" + escape(body) + "</p><p style=\"color:#666;font-size:13px\">You didn't respond within "
							+ DECIDE_AFTER.toMinutes() + " minutes, so Argus made this call. Argus can't place orders at your broker"
							+ " — please act on it there.</p></div>");
		}
		catch (EmailSendException ex) {
			log.warn("Holding guard email to user {} failed: {}", userId, ex.getMessage());
		}
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static BigDecimal money(Double v) {
		return v == null ? null : BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
	}

	/** Package-visible for the day key used in tests. */
	static LocalDate today(Instant now) {
		return now.atZone(NEW_YORK).toLocalDate();
	}
}
