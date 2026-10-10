package com.argus.learning;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * S-B4 — the pattern library over closed paper trades. A past trade's fingerprint is the one stored on it at
 * entry ({@code simulated_trades.setup_fingerprint}), or, for trades opened before the library existed, its
 * recommendation's {@code features} — so the library is useful from day one without a backfill. Every
 * consultation is written to {@code pattern_check}, including NO_PATTERN and SKIP (a skipped entry leaves no
 * trade behind, so this is its only record). Any failure fails open.
 */
@Service
public class PatternLibraryService implements PatternLibrary {

	private static final Logger log = LoggerFactory.getLogger(PatternLibraryService.class);
	/** How many of the most recent closed trades in the same direction the library compares against. */
	static final int LOOKBACK = 1000;

	private final JdbcTemplate jdbc;

	public PatternLibraryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public PatternAdvice consult(Long recommendationId, String ticker, String direction, Set<String> fingerprint) {
		PatternAdvice advice;
		try {
			advice = PatternMatcher.advise(fingerprint, library(direction));
		}
		catch (RuntimeException ex) {
			log.warn("Pattern library: lookup failed for {} {} — proceeding without it: {}", direction, ticker, ex.getMessage());
			return PatternAdvice.noPattern(0, "No prior pattern — the library could not be read; proceeding as planned.");
		}
		try {
			record(recommendationId, ticker, direction, fingerprint, advice);
		}
		catch (RuntimeException ex) {
			log.warn("Pattern library: could not log the check for {} {}: {}", direction, ticker, ex.getMessage());
		}
		return advice;
	}

	List<PatternMatcher.PastTrade> library(String direction) {
		return jdbc.query("""
				select t.id, t.won, t.return_pct, t.exit_reason, coalesce(t.setup_fingerprint, r.features) as fp
				  from simulated_trades t
				  left join recommendations r on r.id = t.recommendation_id
				 where t.status = 'CLOSED' and t.won is not null and t.direction = ?
				   and coalesce(t.setup_fingerprint, r.features) is not null
				 order by t.closed_at desc
				 limit ?
				""", (rs, i) -> new PatternMatcher.PastTrade(rs.getLong("id"), FeatureTokens.fromJson(rs.getString("fp")),
				rs.getBoolean("won"), rs.getBigDecimal("return_pct"), rs.getString("exit_reason")), direction, LOOKBACK);
	}

	private void record(Long recommendationId, String ticker, String direction, Set<String> fingerprint, PatternAdvice a) {
		jdbc.update("""
				insert into pattern_check (recommendation_id, ticker, direction, fingerprint, matches, wins, win_rate_pct,
				    avg_return_pct, stop_out_pct, action, size_multiplier, stop_keep, pattern, note, similar_trade_ids)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", recommendationId, ticker, direction, FeatureTokens.toJson(fingerprint), a.matches(), a.wins(), a.winRatePct(),
				a.avgReturnPct(), a.stopOutPct(), a.action(), a.sizeMultiplier(), a.stopKeep(), a.pattern(), a.note(),
				a.similarTradeIds().isEmpty() ? null
						: a.similarTradeIds().stream().map(String::valueOf).collect(Collectors.joining(",")));
	}

	/** One logged consultation, for the Agents page and the Intelligence ticker view. */
	public record CheckView(long id, Long recommendationId, String ticker, String direction, Instant checkedAt, int matches,
			int wins, Integer winRatePct, BigDecimal avgReturnPct, Integer stopOutPct, String action, String pattern,
			String note) {
	}

	public List<CheckView> recent(String ticker, int limit) {
		int n = Math.max(1, Math.min(limit, 200));
		String where = ticker == null || ticker.isBlank() ? "" : " where ticker = ?";
		Object[] args = ticker == null || ticker.isBlank() ? new Object[] { n } : new Object[] { ticker.trim().toUpperCase(), n };
		return jdbc.query("""
				select id, recommendation_id, ticker, direction, checked_at, matches, wins, win_rate_pct, avg_return_pct,
				       stop_out_pct, action, pattern, note
				  from pattern_check""" + where + " order by checked_at desc, id desc limit ?",
				(rs, i) -> new CheckView(rs.getLong("id"), (Long) rs.getObject("recommendation_id"), rs.getString("ticker"),
						rs.getString("direction"), rs.getTimestamp("checked_at").toInstant(), rs.getInt("matches"),
						rs.getInt("wins"), (Integer) rs.getObject("win_rate_pct"), rs.getBigDecimal("avg_return_pct"),
						(Integer) rs.getObject("stop_out_pct"), rs.getString("action"), rs.getString("pattern"),
						rs.getString("note")),
				args);
	}

	/** How the library's advice has played out: counts by action over the last {@code days}. */
	public java.util.Map<String, Long> actionCounts(int days) {
		java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
		jdbc.query("select action, count(*) c from pattern_check where checked_at >= ? group by action order by action",
				rs -> {
					out.put(rs.getString("action"), rs.getLong("c"));
				}, Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(days))));
		return out;
	}
}
