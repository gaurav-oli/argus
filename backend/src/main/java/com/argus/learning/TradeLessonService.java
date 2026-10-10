package com.argus.learning;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-B3: keeps a lesson for every closed paper trade and settles what changed after it.
 *
 * <p>Deliberately <b>not</b> hooked into the close itself: a short pass (every 5 minutes) writes lessons for
 * any newly closed trade — the Analyst's post-mortem is already saved by then — so nothing here can slow or
 * break a close. The same pass backfills older closed trades a batch at a time, and re-checks lessons still
 * {@code PENDING} against the nightly logic review and Agent 13 runs. No model call: the words come from
 * {@link LessonComposer} over stored facts.
 */
@Service
public class TradeLessonService {

	private static final Logger log = LoggerFactory.getLogger(TradeLessonService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Closed trades turned into lessons per pass (backfill drains over a few passes). */
	static final int BATCH = 200;

	private final JdbcTemplate jdbc;

	@Value("${argus.lessons.enabled:true}")
	private boolean enabled = true;

	public TradeLessonService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** One lesson as the UI reads it. */
	public record LessonView(long id, long tradeId, Long recommendationId, String ticker, String direction, boolean won,
			String exitReason, Instant closedAt, String whyEntered, String outcome, String lesson,
			List<String> reliedOn, String changeKind, String changeSummary, Instant changeCheckedAt) {
	}

	@Scheduled(fixedDelayString = "${argus.lessons.pass-ms:300000}", initialDelayString = "${argus.lessons.initial-delay-ms:60000}")
	public void scheduledPass() {
		if (enabled) {
			try {
				pass(Instant.now());
			} catch (RuntimeException ex) {
				log.warn("Trade lessons pass failed: {}", ex.getMessage());
			}
		}
	}

	/** Write missing lessons, then settle pending changes. Returns {written, settled}. */
	public int[] pass(Instant now) {
		int written = writeMissing();
		int settled = settlePending(now);
		if (written + settled > 0) {
			log.info("Trade lessons: {} written, {} changes settled", written, settled);
		}
		return new int[] {written, settled};
	}

	int writeMissing() {
		List<Map<String, Object>> rows = jdbc.queryForList("select t.id, t.recommendation_id, t.ticker, t.direction, t.won, t.return_pct,"
				+ " t.excess_return_pct, t.exit_reason, t.entry_at, t.closed_at, t.review from simulated_trades t"
				+ " where t.status = 'CLOSED' and t.closed_at is not null and t.won is not null"
				+ " and not exists (select 1 from trade_lesson l where l.trade_id = t.id)"
				+ " order by t.closed_at desc limit " + BATCH);
		int n = 0;
		for (Map<String, Object> r : rows) {
			long tradeId = ((Number) r.get("id")).longValue();
			Long recId = r.get("recommendation_id") == null ? null : ((Number) r.get("recommendation_id")).longValue();
			LessonComposer.TradeFacts t = new LessonComposer.TradeFacts((String) r.get("ticker"), (String) r.get("direction"),
					Boolean.TRUE.equals(r.get("won")), (BigDecimal) r.get("return_pct"), (BigDecimal) r.get("excess_return_pct"),
					(String) r.get("exit_reason"), instant(r.get("entry_at")), instant(r.get("closed_at")), (String) r.get("review"));
			LessonComposer.Composed c = LessonComposer.compose(t, recId == null ? null : call(recId));
			n += jdbc.update("insert into trade_lesson (trade_id, recommendation_id, ticker, direction, won, exit_reason, closed_at,"
					+ " why_entered, outcome, lesson, relied_on) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (trade_id) do nothing",
					tradeId, recId, t.ticker(), t.direction(), t.won(), t.exitReason(), Timestamp.from(t.closedAt()),
					c.whyEntered(), c.outcome(), c.lesson(), String.join(",", c.reliedOn()));
		}
		return n;
	}

	private LessonComposer.CallFacts call(long recId) {
		List<Map<String, Object>> rec = jdbc.queryForList(
				"select action, conviction_score, bull_probability, thesis from recommendations where id = ?", recId);
		if (rec.isEmpty()) {
			return null;
		}
		Map<String, Object> r = rec.get(0);
		List<LessonComposer.Signal> signals = jdbc.query(
				"select agent, direction, signed_weight from recommendation_signals where recommendation_id = ?",
				(rs, i) -> new LessonComposer.Signal(rs.getString("agent"), rs.getString("direction"), rs.getBigDecimal("signed_weight")),
				recId);
		String action = (String) r.get("action");
		return new LessonComposer.CallFacts(actionLabel(action),
				r.get("conviction_score") == null ? null : ((Number) r.get("conviction_score")).intValue(),
				(BigDecimal) r.get("bull_probability"), (String) r.get("thesis"), signals);
	}

	int settlePending(Instant now) {
		List<Map<String, Object>> pending = jdbc.queryForList(
				"select id, closed_at, relied_on from trade_lesson where change_kind = 'PENDING' order by closed_at limit " + BATCH);
		int n = 0;
		for (Map<String, Object> p : pending) {
			Instant closedAt = instant(p.get("closed_at"));
			String relied = (String) p.get("relied_on");
			LessonComposer.Change ch = LessonComposer.resolveChange(closedAt,
					relied == null || relied.isBlank() ? List.of() : Arrays.asList(relied.split(",")),
					reviewsSince(closedAt), rulesSince(closedAt), learnerRanSince(closedAt), now);
			if (!ch.pending()) {
				n += jdbc.update("update trade_lesson set change_kind = ?, change_summary = ?, change_checked_at = ? where id = ?",
						ch.kind(), ch.summary(), Timestamp.from(now), ((Number) p.get("id")).longValue());
			}
		}
		return n;
	}

	/** Logic-review runs after the close, oldest first (the first adopted one is the change that followed). */
	private List<LessonComposer.Review> reviewsSince(Instant closedAt) {
		return jdbc.query("select ran_at, adopted, reason, proposals::text as proposals from logic_review where ran_at > ? order by ran_at",
				(rs, i) -> new LessonComposer.Review(rs.getTimestamp("ran_at").toInstant(), rs.getBoolean("adopted"),
						rs.getString("reason"), proposals(rs.getString("proposals"))),
				Timestamp.from(closedAt));
	}

	private List<LessonComposer.RuleChange> rulesSince(Instant closedAt) {
		Timestamp t = Timestamp.from(closedAt);
		List<LessonComposer.RuleChange> out = new ArrayList<>(jdbc.query(
				"select activated_at, description from learned_rule where activated_at > ? order by activated_at",
				(rs, i) -> new LessonComposer.RuleChange(rs.getTimestamp("activated_at").toInstant(), true, rs.getString("description")), t));
		out.addAll(jdbc.query("select retired_at, description from learned_rule where retired_at > ? order by retired_at",
				(rs, i) -> new LessonComposer.RuleChange(rs.getTimestamp("retired_at").toInstant(), false, rs.getString("description")), t));
		return out;
	}

	private boolean learnerRanSince(Instant closedAt) {
		Integer n = jdbc.queryForObject("select count(*) from learning_report where created_at > ?", Integer.class, Timestamp.from(closedAt));
		return n != null && n > 0;
	}

	private static List<LessonComposer.Proposal> proposals(String json) {
		List<LessonComposer.Proposal> out = new ArrayList<>();
		if (json == null || json.isBlank()) {
			return out;
		}
		try {
			for (JsonNode n : JSON.readTree(json)) {
				out.add(new LessonComposer.Proposal(n.path("agent").asString(""), n.path("factor").asDouble(1.0), n.path("why").asString("")));
			}
		} catch (RuntimeException ex) {
			log.debug("Unreadable logic_review proposals: {}", ex.getMessage());
		}
		return out;
	}

	// ---- reads ----

	public List<LessonView> recent(String ticker, int limit) {
		int lim = Math.max(1, Math.min(limit, 200));
		String sql = "select * from trade_lesson" + (ticker == null || ticker.isBlank() ? "" : " where upper(ticker) = upper(?)")
				+ " order by closed_at desc limit " + lim;
		return ticker == null || ticker.isBlank() ? jdbc.query(sql, (rs, i) -> view(rs)) : jdbc.query(sql, (rs, i) -> view(rs), ticker.trim());
	}

	public java.util.Optional<LessonView> forTrade(long tradeId) {
		return jdbc.query("select * from trade_lesson where trade_id = ?", (rs, i) -> view(rs), tradeId).stream().findFirst();
	}

	private static LessonView view(java.sql.ResultSet rs) throws java.sql.SQLException {
		String relied = rs.getString("relied_on");
		Timestamp checked = rs.getTimestamp("change_checked_at");
		long rec = rs.getLong("recommendation_id");
		return new LessonView(rs.getLong("id"), rs.getLong("trade_id"), rs.wasNull() ? null : rec, rs.getString("ticker"),
				rs.getString("direction"), rs.getBoolean("won"), rs.getString("exit_reason"), rs.getTimestamp("closed_at").toInstant(),
				rs.getString("why_entered"), rs.getString("outcome"), rs.getString("lesson"),
				relied == null || relied.isBlank() ? List.of() : Arrays.stream(relied.split(",")).map(LessonComposer::agentName).toList(),
				rs.getString("change_kind"), rs.getString("change_summary"), checked == null ? null : checked.toInstant());
	}

	private static String actionLabel(String action) {
		if (action == null) {
			return null;
		}
		return switch (action) {
			case "STRONG_BUY" -> "Strong buy";
			case "BUY" -> "Buy";
			case "WATCH" -> "Watch";
			case "AVOID" -> "Avoid";
			case "STRONG_AVOID" -> "Strong avoid";
			default -> action;
		};
	}

	private static Instant instant(Object o) {
		if (o == null) {
			return null;
		}
		if (o instanceof Timestamp ts) {
			return ts.toInstant();
		}
		if (o instanceof java.time.OffsetDateTime odt) {
			return odt.toInstant();
		}
		return (Instant) o;
	}
}
