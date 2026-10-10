package com.argus.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.argus.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** S-B3: a closed paper trade gets a lesson; "what changed" stays PENDING until the nightly jobs settle it. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TradeLessonIntegrationTest {

	@Autowired
	TradeLessonService lessons;

	@Autowired
	JdbcTemplate jdbc;

	private final Instant closed = Instant.parse("2026-10-01T15:00:00Z");
	private long tradeId;

	@BeforeEach
	void closedTrade() {
		jdbc.update("delete from trade_lesson");
		jdbc.update("delete from simulated_trades");
		jdbc.update("delete from logic_review");
		jdbc.update("delete from learning_report");
		jdbc.update("delete from learned_rule");
		Long recId = jdbc.queryForObject("insert into recommendations (ticker, direction, bull_probability, bear_probability, confidence,"
				+ " action, conviction_score, thesis) values ('DOL.TO', 'BULLISH', 0.71, 0.29, 0.8, 'BUY', 72, 'Insider buying. More.') returning id",
				Long.class);
		jdbc.update("insert into recommendation_signals (recommendation_id, agent, direction, weight, signed_weight) values"
				+ " (?, 'agent-11-deep', 'BULLISH', 1.4, 1.4), (?, 'agent-2-social', 'BEARISH', 0.5, -0.5)", recId, recId);
		tradeId = jdbc.queryForObject("insert into simulated_trades (recommendation_id, ticker, direction, notional, entry_price, shares,"
				+ " horizon_days, status, entry_at, closed_at, exit_price, return_pct, excess_return_pct, won, exit_reason, review)"
				+ " values (?, 'DOL.TO', 'BULLISH', 100, 180, 0.555, 30, 'CLOSED', ?, ?, 175, -2.78, -3.5, false, 'STOP',"
				+ " 'The stop broke as rates rose.') returning id",
				Long.class, recId, Timestamp.from(closed.minus(Duration.ofDays(12))), Timestamp.from(closed));
		// An open trade must not get a lesson.
		jdbc.update("insert into simulated_trades (ticker, direction, notional, entry_price, shares, horizon_days, status)"
				+ " values ('NVDA', 'BULLISH', 100, 180, 0.5, 30, 'OPEN')");
	}

	@Test
	void writesALessonForTheClosedTradeOnlyAndWaitsForTheNightlyJobs() {
		int[] r = lessons.pass(closed.plus(Duration.ofHours(1)));
		assertThat(r[0]).isEqualTo(1);
		TradeLessonService.LessonView v = lessons.forTrade(tradeId).orElseThrow();
		assertThat(v.whyEntered()).startsWith("Went long DOL.TO on a buy call (conviction 72, 71% odds on its side). Driven by Deep Analyst.");
		assertThat(v.outcome()).startsWith("Lost -2.8% (-3.5 pts vs SPY) — the stop was hit");
		assertThat(v.lesson()).isEqualTo("The stop broke as rates rose.");
		assertThat(v.reliedOn()).containsExactly("Deep Analyst");
		assertThat(v.changeKind()).isEqualTo("PENDING");
		assertThat(lessons.recent(null, 10)).hasSize(1);
		assertThat(lessons.recent("dol.to", 10)).hasSize(1);
		assertThat(lessons.recent("NVDA", 10)).isEmpty();

		// Idempotent: a second pass writes nothing new.
		assertThat(lessons.pass(closed.plus(Duration.ofHours(2)))[0]).isZero();
	}

	@Test
	void anAdoptedLogicReviewAfterTheCloseSettlesAsAWeightChange() {
		lessons.pass(closed.plus(Duration.ofHours(1)));
		String proposals = "[{\"agent\":\"agent-11-deep\",\"factor\":1.1,\"why\":\"beat SPY\"}]";
		jdbc.update("insert into logic_review (ran_at, adopted, reason, proposals) values (?, true, 'Brier improved', cast(? as jsonb))",
				Timestamp.from(closed.plus(Duration.ofHours(12))), proposals);
		int[] r = lessons.pass(closed.plus(Duration.ofDays(1)));
		assertThat(r[1]).isEqualTo(1);
		TradeLessonService.LessonView v = lessons.forTrade(tradeId).orElseThrow();
		assertThat(v.changeKind()).isEqualTo("WEIGHTS_ADJUSTED");
		assertThat(v.changeSummary()).contains("Deep Analyst ×1.10 (this trade relied on it)");
		assertThat(v.changeCheckedAt()).isNotNull();
	}

	@Test
	void bothNightlyJobsRunningWithNoChangeIsAnExplicitNoChange() {
		lessons.pass(closed.plus(Duration.ofHours(1)));
		jdbc.update("insert into logic_review (ran_at, adopted, reason) values (?, false, 'proposal hurt accuracy')",
				Timestamp.from(closed.plus(Duration.ofHours(12))));
		jdbc.update("insert into learning_report (created_at, trades_analyzed, clusters) values (?, 40, 3)",
				Timestamp.from(closed.plus(Duration.ofHours(13))));
		// A review that ran BEFORE the close must not count.
		jdbc.update("insert into logic_review (ran_at, adopted, reason) values (?, true, 'old')", Timestamp.from(closed.minus(Duration.ofDays(1))));
		lessons.pass(closed.plus(Duration.ofDays(1)));
		TradeLessonService.LessonView v = lessons.forTrade(tradeId).orElseThrow();
		assertThat(v.changeKind()).isEqualTo("NO_CHANGE");
		assertThat(v.changeSummary()).contains("kept the weights (proposal hurt accuracy)").contains("Agent 13 changed no rules");
	}
}
