package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.TestcontainersConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Paired scores become one prediction per virtual agent per ticker-day, from the same articles on both sides. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ModelExperimentIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ModelExperiment experiment;

	@Test
	void pairedScoresBecomeVirtualAgentPredictions() {
		LocalDate day = LocalDate.of(2026, 11, 2);
		jdbc.update("delete from signal_grade where ticker = 'ZZEXP'");
		jdbc.update("delete from model_experiment where ticker = 'ZZEXP'");
		jdbc.update("insert into model_experiment (article_id, ticker, call_date, model, local_score, claude_score) values (9001, 'ZZEXP', ?, 'm', 0.6, -0.5)", day);
		jdbc.update("insert into model_experiment (article_id, ticker, call_date, model, local_score, claude_score) values (9002, 'ZZEXP', ?, 'm', 0.4, -0.3)", day);
		// an article the stronger model didn't score is left out of BOTH sides
		jdbc.update("insert into model_experiment (article_id, ticker, call_date, model, local_score, claude_score) values (9003, 'ZZEXP', ?, 'm', -0.9, null)", day);

		experiment.writePredictions(day);

		List<Map<String, Object>> rows = jdbc.queryForList(
				"select agent, direction, strength from signal_grade where ticker = 'ZZEXP' order by agent");
		assertEquals(2, rows.size());
		assertEquals(ModelExperiment.CLAUDE_AGENT, rows.get(0).get("agent"));
		assertEquals("BEARISH", rows.get(0).get("direction"));
		assertEquals(0.4, (Double) rows.get(0).get("strength"), 1e-9);
		assertEquals(ModelExperiment.LOCAL_AGENT, rows.get(1).get("agent"));
		assertEquals("BULLISH", rows.get(1).get("direction"));
		assertEquals(0.5, (Double) rows.get(1).get("strength"), 1e-9);
	}
}
