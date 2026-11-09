package com.argus.grading;

import com.argus.grading.GateMath.Outcome;
import com.argus.grading.ReportCardMath.Card;
import com.argus.grading.StrategyGateService.Status;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phase 1's pre-registered pass test and automatic switch-off (docs/strategy-rebuild-plan.md, Phase 1 §3–4).
 *
 * <ol>
 *   <li><b>Backfill check.</b> Weights fitted only on predictions up to {@link #FIT_UNTIL} are scored on calls from
 *       {@link #TEST_FROM} onward, which they never saw, against the weights Agent 5 actually used. The strongest
 *       third of calls under each weighting are the ones "acted on".</li>
 *   <li><b>Forward check.</b> Every live call since deployment carries the shadow verdict; once their 20-day windows
 *       close, the shadow's actionable calls are compared with the live ones.</li>
 * </ol>
 * Both must beat the live weights by {@link #MARGIN_PTS} points of average 20-day excess return, with a positive
 * BUY − AVOID spread, on at least {@link GateMath#MIN_CLUSTERS} independent clusters. Only then does the gate go LIVE.
 *
 * <p>Switch-off when live: the live calls' 20-day BUY − AVOID spread turns negative over ≥ 30 clusters, or the paper
 * book trails SPY by ≥ {@link #REVERT_BOOK_PTS} points (notional-weighted, ≥ {@link #REVERT_MIN_TRADES} closed trades)
 * since the switch.
 */
@Component
public class Phase1Gate implements GateEvaluator {

	private static final Logger log = LoggerFactory.getLogger(Phase1Gate.class);

	static final LocalDate FIT_UNTIL = LocalDate.of(2026, 10, 16);
	static final LocalDate TEST_FROM = LocalDate.of(2026, 10, 19);
	static final double MARGIN_PTS = 1.0;
	static final double REVERT_BOOK_PTS = -8.0;
	static final int REVERT_MIN_TRADES = 10;
	static final int H = 20;
	/** The strongest third of calls under a weighting count as acted on in the backfill check. */
	static final double ACT_SHARE = 1.0 / 3.0;
	private static final Set<String> ACTIONABLE = Set.of("BUY", "STRONG_BUY", "AVOID", "STRONG_AVOID");
	private static final Set<String> BULLISH = Set.of("BUY", "STRONG_BUY");

	private final JdbcTemplate jdbc;
	private final ReportCardService cards;
	private final StrategyGateService gates;
	private final NotificationService notifications;

	public Phase1Gate(JdbcTemplate jdbc, ReportCardService cards, StrategyGateService gates,
			NotificationService notifications) {
		this.jdbc = jdbc;
		this.cards = cards;
		this.gates = gates;
		this.notifications = notifications;
	}

	@Override
	public String gate() {
		return StrategyGateService.PHASE1_WEIGHTS;
	}

	public record Check(String verdict, int clusters, Double newMean, Double liveMean, Double spread, String note) {

		static Check pending(int clusters, String note) {
			return new Check("PENDING", clusters, null, null, null, note);
		}
	}

	@Override
	public void evaluate() {
		Status status = gates.status(gate());
		if (status == Status.LIVE) {
			checkSwitchOff();
			return;
		}
		Check backfill = backfillCheck();
		Check forward = forwardCheck();
		String details = GateMath.json("backfill", backfill.verdict(), "backfillClusters", backfill.clusters(),
				"backfillNewMean", backfill.newMean(), "backfillLiveMean", backfill.liveMean(), "backfillSpread",
				backfill.spread(), "backfillNote", backfill.note(), "forward", forward.verdict(), "forwardClusters",
				forward.clusters(), "forwardNewMean", forward.newMean(), "forwardLiveMean", forward.liveMean(),
				"forwardSpread", forward.spread(), "forwardNote", forward.note());
		if (status == Status.FAILED || status == Status.REVERTED) {
			gates.record(gate(), status, details, "held " + status.name().toLowerCase() + " — needs a manual review");
			return;
		}
		if ("FAIL".equals(backfill.verdict()) || "FAIL".equals(forward.verdict())) {
			gates.record(gate(), Status.FAILED, details, "pass test failed — report-card weights stay in shadow");
			alert(UrgencyTier.IMPORTANT, "Report-card weights failed their test",
					"The new agent weights did not beat the current ones on unseen calls, so Argus keeps the current weights."
							+ " See the Agents page for the numbers.");
			return;
		}
		if ("PASS".equals(backfill.verdict()) && "PASS".equals(forward.verdict())) {
			gates.record(gate(), Status.LIVE, details, "backfill and forward checks passed — report-card weights live");
			alert(UrgencyTier.IMPORTANT, "Report-card weights are now live",
					"Both pre-registered checks passed: on calls they never saw, the new weights beat the old ones. Agent 5 now"
							+ " weighs each agent by its report card. The old tuning loop is switched off.");
			return;
		}
		gates.record(gate(), Status.SHADOW, details, "waiting for enough graded calls");
	}

	/** Backfill check: fit on early predictions, score on later calls (see class doc). */
	Check backfillCheck() {
		Map<String, Card> fitted = cards.fittedUntil(FIT_UNTIL);
		Map<String, double[]> live = new LinkedHashMap<>(); // ticker|date -> {liveNet, newNet}
		Map<String, Object[]> meta = new HashMap<>(); // ticker|date -> {date, sector, excess20}
		jdbc.query("""
				select g.agent, g.ticker, g.call_date, g.direction, g.strength, r.sector, r.excess_20
				from signal_grade g
				join rec_grade r on r.ticker = g.ticker and r.call_date = g.call_date
				where g.call_date >= ? and r.excess_20 is not null and g.direction in ('BULLISH', 'BEARISH')""", rs -> {
			String key = rs.getString("ticker") + "|" + rs.getDate("call_date");
			int sign = "BULLISH".equals(rs.getString("direction")) ? 1 : -1;
			double strength = rs.getDouble("strength");
			Card c = fitted.get(rs.getString("agent"));
			double m = c == null || c.state() == ReportCardMath.State.UNGRADED ? 1.0 : c.target();
			double s2 = c != null && Boolean.FALSE.equals(c.strengthUseful()) && c.meanStrength() > 0 ? c.meanStrength() : strength;
			double[] v = live.computeIfAbsent(key, k -> new double[2]);
			v[0] += sign * strength;
			v[1] += sign * s2 * m;
			meta.putIfAbsent(key, new Object[] {rs.getDate("call_date").toLocalDate(), rs.getString("sector"),
					rs.getDouble("excess_20")});
		}, TEST_FROM);
		if (live.isEmpty()) {
			return Check.pending(0, "no test-window calls have a 20-day result yet");
		}
		List<Outcome> liveActed = acted(live, meta, 0, null);
		List<Outcome> newBull = new ArrayList<>();
		List<Outcome> newBear = new ArrayList<>();
		List<Outcome> newActed = acted(live, meta, 1, new List[] {newBull, newBear});
		ReportCardMath.Grade gNew = GateMath.pooled(newActed, H);
		ReportCardMath.Grade gLive = GateMath.pooled(liveActed, H);
		Double spread = spread(newBull, newBear);
		if (gNew.clusters() < GateMath.MIN_CLUSTERS) {
			return new Check("PENDING", gNew.clusters(), gNew.edge(), gLive.edge(), spread,
					"needs " + GateMath.MIN_CLUSTERS + " independent clusters");
		}
		boolean pass = gNew.edge() - gLive.edge() >= MARGIN_PTS && spread != null && spread > 0;
		return new Check(pass ? "PASS" : "FAIL", gNew.clusters(), gNew.edge(), gLive.edge(), spread, null);
	}

	/**
	 * The strongest {@link #ACT_SHARE} of calls under weighting {@code idx} (0 live, 1 new), each scored as the
	 * direction of its net lean times the stock's 20-day excess return. Optionally splits bullish/bearish raw excess.
	 */
	@SuppressWarnings("unchecked")
	private static List<Outcome> acted(Map<String, double[]> nets, Map<String, Object[]> meta, int idx, List<Outcome>[] split) {
		List<Map.Entry<String, double[]>> sorted = new ArrayList<>(nets.entrySet());
		sorted.removeIf(e -> e.getValue()[idx] == 0);
		sorted.sort((a, b) -> Double.compare(Math.abs(b.getValue()[idx]), Math.abs(a.getValue()[idx])));
		int n = (int) Math.ceil(sorted.size() * ACT_SHARE);
		List<Outcome> out = new ArrayList<>();
		for (Map.Entry<String, double[]> e : sorted.subList(0, Math.min(n, sorted.size()))) {
			Object[] m = meta.get(e.getKey());
			double excess = (Double) m[2];
			int sign = e.getValue()[idx] > 0 ? 1 : -1;
			out.add(new Outcome((LocalDate) m[0], (String) m[1], sign * excess));
			if (split != null) {
				split[sign > 0 ? 0 : 1].add(new Outcome((LocalDate) m[0], (String) m[1], excess));
			}
		}
		return out;
	}

	/** Forward check: the shadow verdicts stamped on live calls vs the live verdicts, once 20 days have passed. */
	Check forwardCheck() {
		List<Outcome> live = new ArrayList<>();
		List<Outcome> shadow = new ArrayList<>();
		List<Outcome> shadowBull = new ArrayList<>();
		List<Outcome> shadowBear = new ArrayList<>();
		jdbc.query("""
				select call_date, sector, action, shadow_action, excess_20 from rec_grade
				where shadow_action is not null and excess_20 is not null""", rs -> {
			LocalDate d = rs.getDate("call_date").toLocalDate();
			String sector = rs.getString("sector");
			double ex = rs.getDouble("excess_20");
			String a = rs.getString("action");
			String sa = rs.getString("shadow_action");
			if (ACTIONABLE.contains(a)) live.add(new Outcome(d, sector, (BULLISH.contains(a) ? 1 : -1) * ex));
			if (ACTIONABLE.contains(sa)) {
				boolean bull = BULLISH.contains(sa);
				shadow.add(new Outcome(d, sector, (bull ? 1 : -1) * ex));
				(bull ? shadowBull : shadowBear).add(new Outcome(d, sector, ex));
			}
		});
		ReportCardMath.Grade gShadow = GateMath.pooled(shadow, H);
		ReportCardMath.Grade gLive = GateMath.pooled(live, H);
		Double spread = spread(shadowBull, shadowBear);
		if (gShadow.clusters() < GateMath.MIN_CLUSTERS) {
			return new Check("PENDING", gShadow.clusters(), gShadow.clusters() == 0 ? null : gShadow.edge(),
					gLive.clusters() == 0 ? null : gLive.edge(), spread,
					"shadow calls need 20 trading days to grade and " + GateMath.MIN_CLUSTERS + " clusters");
		}
		boolean pass = gShadow.edge() - gLive.edge() >= MARGIN_PTS && spread != null && spread > 0;
		return new Check(pass ? "PASS" : "FAIL", gShadow.clusters(), gShadow.edge(), gLive.edge(), spread, null);
	}

	/** BUY − AVOID spread: average excess of bullish calls minus bearish calls (cluster-pooled), null if one side is empty. */
	static Double spread(List<Outcome> bull, List<Outcome> bear) {
		if (bull.isEmpty() || bear.isEmpty()) {
			return null;
		}
		return GateMath.pooled(bull, H).edge() - GateMath.pooled(bear, H).edge();
	}

	/** The automatic switch-off, checked nightly while live. */
	void checkSwitchOff() {
		Instant since = gates.switchedAt(gate()).orElse(Instant.EPOCH);
		LocalDate sinceDate = since.atZone(ZoneId.of("America/New_York")).toLocalDate();
		List<Outcome> bull = new ArrayList<>();
		List<Outcome> bear = new ArrayList<>();
		jdbc.query("""
				select call_date, sector, action, excess_20 from rec_grade
				where call_date >= ? and excess_20 is not null and action in ('BUY','STRONG_BUY','AVOID','STRONG_AVOID')""",
				rs -> {
					Outcome o = new Outcome(rs.getDate("call_date").toLocalDate(), rs.getString("sector"), rs.getDouble("excess_20"));
					(BULLISH.contains(rs.getString("action")) ? bull : bear).add(o);
				}, sinceDate);
		int clusters = GateMath.pooled(concat(bull, bear), H).clusters();
		Double spread = spread(bull, bear);
		Map<String, Object> book = jdbc.queryForMap("""
				select count(*) as n, sum(notional * excess_return_pct) / nullif(sum(notional), 0) as ex
				from simulated_trades where status = 'CLOSED' and closed_at >= ? and excess_return_pct is not null""",
				java.sql.Timestamp.from(since));
		long n = ((Number) book.get("n")).longValue();
		Double bookEx = book.get("ex") == null ? null : ((Number) book.get("ex")).doubleValue();
		String details = GateMath.json("liveClusters", clusters, "liveSpread", spread, "bookTrades", n, "bookExcess", bookEx);
		String reason = null;
		if (clusters >= GateMath.MIN_CLUSTERS && spread != null && spread < 0) {
			reason = String.format(java.util.Locale.ROOT, "live BUY − AVOID spread turned negative (%.2f pts over %d clusters)",
					spread, clusters);
		}
		else if (n >= REVERT_MIN_TRADES && bookEx != null && bookEx <= REVERT_BOOK_PTS) {
			reason = String.format(java.util.Locale.ROOT, "paper book trails SPY by %.1f pts over %d trades since the switch",
					-bookEx, n);
		}
		if (reason == null) {
			gates.record(gate(), Status.LIVE, details, "live; switch-off triggers clear");
			return;
		}
		gates.record(gate(), Status.REVERTED, details, "automatic switch-off: " + reason);
		log.warn("Phase 1 switch-off: {}", reason);
		alert(UrgencyTier.CRITICAL, "Report-card weights switched off",
				"Automatic switch-off fired: " + reason + ". Agent 5 is back on its previous weights until reviewed.");
	}

	private static List<Outcome> concat(List<Outcome> a, List<Outcome> b) {
		List<Outcome> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private void alert(UrgencyTier tier, String title, String body) {
		try {
			notifications.notify(Notification.of(tier, title, body, "/agents"));
		}
		catch (RuntimeException ex) {
			log.warn("Gate alert failed: {}", ex.getMessage());
		}
	}
}
