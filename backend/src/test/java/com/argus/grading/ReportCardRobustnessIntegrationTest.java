package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** One wild result can't decide a grade: results are capped, sub-$5 stocks aren't graded, changed agents restart. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportCardRobustnessIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ReportCardService cards;

	private void grade(String agent, String ticker, LocalDate day, double entry, double excess) {
		jdbc.update("""
				insert into signal_grade (agent, ticker, call_date, direction, strength, sector, entry_close, excess_5, excess_10, excess_20)
				values (?, ?, ?, 'BEARISH', 0.5, 'ZZSECT', ?, ?, ?, ?)""", agent, ticker, day, entry, excess, excess, excess);
	}

	@Test
	void capsResultsSkipsPennyStocksAndRestartsChangedAgents() {
		jdbc.update("delete from signal_grade where sector = 'ZZSECT'");
		LocalDate d = LocalDate.of(2026, 10, 1);
		grade("zz-agent", "ZZBIG", d, 50, 400);     // a +400% spike against a bearish call
		grade("zz-agent", "ZZPENNY", d.plusDays(1), 0.8, 300); // sub-$5: not graded at all
		grade("agent-1-news", "ZZOLD", d, 50, 5);  // before News's restart date

		List<ReportCardMath.Obs> obs = cards.load(null).get("zz-agent");
		assertEquals(1, obs.size());
		assertEquals(ReportCardService.EXCESS_CAP, obs.get(0).excess(), 1e-9);

		cards.recompute();
		Integer calls = jdbc.queryForObject("select calls from agent_report_card where agent = 'agent-1-news'", Integer.class);
		assertTrue(calls != null && calls == jdbc.queryForObject(
				"select count(*) from signal_grade where agent = 'agent-1-news' and call_date >= '2026-11-10' and excess_5 is not null and direction in ('BULLISH','BEARISH') and (entry_close is null or entry_close >= 5)",
				Integer.class));
		jdbc.update("delete from signal_grade where sector = 'ZZSECT'");
		jdbc.update("delete from agent_report_card where agent = 'zz-agent'");
	}
}
