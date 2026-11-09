package com.argus.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The budget status splits Claude calls into CLI (subscription) and API key, counting failed CLI calls apart. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CliCallCountIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	CostGovernor governor;

	@Test
	void countsCliCallsThisMonthSeparatelyFromFailures() {
		jdbc.update("delete from claude_cli_calls");
		jdbc.update("insert into claude_cli_calls (model, source, input_tokens, output_tokens, outcome) values ('m', 'escalation', 10, 20, 'ok')");
		jdbc.update("insert into claude_cli_calls (model, source, input_tokens, output_tokens, outcome) values ('m', 'model_experiment', 5, 1, 'ok')");
		jdbc.update("insert into claude_cli_calls (model, source, outcome) values ('m', 'escalation', 'failed')");
		// last month's call doesn't count toward this month
		jdbc.update("insert into claude_cli_calls (occurred_at, model, source, outcome) values (now() - interval '40 days', 'm', 'escalation', 'ok')");

		BudgetStatus s = governor.status();
		assertEquals(2, s.cliCalls());
		assertEquals(1, s.cliFailures());
	}
}
