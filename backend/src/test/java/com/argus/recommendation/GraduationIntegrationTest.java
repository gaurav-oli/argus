package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.argus.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Graduation state machine driven by recorded outcomes against real Postgres (Story 6.6). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GraduationIntegrationTest {

	@Autowired
	GraduationService service;

	@Autowired
	AgentGraduationRepository graduation;

	@Autowired
	PaperTradeRepository trades;

	@BeforeEach
	void reset() {
		trades.deleteAll();
		graduation.save(new AgentGraduation()); // id=1, state SHADOW
	}

	private void record(int wins, int losses) {
		for (int i = 0; i < wins; i++) {
			service.recordOutcome(true, null);
		}
		for (int i = 0; i < losses; i++) {
			service.recordOutcome(false, null);
		}
	}

	@Test
	void promotesToProbationAfterTwentyTradesAtSeventyPercent() {
		record(14, 6); // 20 trades, 70%; last-10 = 6L+4W = 40% (no freeze)
		assertEquals(GraduationState.PROBATION, service.currentState());
	}

	@Test
	void freezesOnaSeriousFailureRun() {
		record(0, 10); // 10 straight losses ⇒ rolling 0% < 30%
		assertEquals(GraduationState.FROZEN, service.currentState());
	}

	@Test
	void staysInShadowWhileUnproven() {
		record(5, 5); // 50%, and only 10 trades
		assertEquals(GraduationState.SHADOW, service.currentState());
	}
}
