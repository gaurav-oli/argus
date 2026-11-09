package com.argus.grading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.grading.ReportCardMath.Card;
import com.argus.grading.ReportCardMath.Obs;
import com.argus.grading.ReportCardMath.State;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportCardMathTest {

	private static final LocalDate START = LocalDate.of(2026, 1, 5);

	/** {@code n} independent clusters (distinct period × sector), each one call with the given edge pattern. */
	private static List<Obs> calls(int n, double edge, double noise, String regime) {
		List<Obs> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double e = edge + (i % 2 == 0 ? noise : -noise);
			out.add(new Obs(START.plusDays(7L * i), "S" + (i % 3), 0.5 + (i % 5) * 0.1, 1, e, regime, false));
		}
		return out;
	}

	@Test
	void repeatsOnTheSameDayAndSectorCountAsOneCluster() {
		List<Obs> obs = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			obs.add(new Obs(START, "TECH", 0.5, 1, 2.0, "CALM", false));
		}
		ReportCardMath.Grade g = ReportCardMath.grade(obs, 5);
		assertEquals(50, g.calls());
		assertEquals(1, g.clusters());
	}

	@Test
	void ungradedBelowMinimumKeepsCurrentWeight() {
		Card c = ReportCardMath.card("agent-1-news", calls(10, 3.0, 1.0, "CALM"), 0.9, Map.of());
		assertEquals(State.UNGRADED, c.state());
		assertEquals(0.9, c.multiplier());
	}

	@Test
	void reliablyRightAgentEarnsMoreWeightGradually() {
		Card c = ReportCardMath.card("agent-1-news", calls(60, 3.0, 1.0, "CALM"), 1.0, Map.of());
		assertEquals(State.GOOD, c.state());
		assertTrue(c.target() > 1.0 && c.target() <= ReportCardMath.MAX_MULTIPLIER);
		assertEquals(1.1, c.multiplier(), 1e-9); // one step, not a jump to target
	}

	@Test
	void coinFlipAgentIsReduced() {
		Card c = ReportCardMath.card("agent-1-news", calls(60, 0.0, 3.0, "CALM"), 1.0, Map.of());
		assertEquals(State.COIN_FLIP, c.state());
		// 0.6, less the overconfidence penalty when its strong calls are no better than its weak ones
		assertEquals(Boolean.FALSE.equals(c.strengthUseful()) ? 0.6 * 0.85 : 0.6, c.target(), 1e-9);
	}

	@Test
	void reliablyWrongInOneMoodIsMutedNotFlipped() {
		Card c = ReportCardMath.card("agent-8-macro", calls(60, -3.0, 1.0, "RISK_OFF"), 1.0, Map.of());
		assertEquals(State.MUTED, c.state());
		assertEquals(0.0, c.target());
	}

	@Test
	void reliablyWrongInBothMoodsIsFlipped() {
		List<Obs> obs = new ArrayList<>(calls(40, -3.0, 1.0, "RISK_OFF"));
		for (Obs o : calls(40, -3.0, 1.0, "CALM")) {
			obs.add(new Obs(o.date().plusDays(3), "X" + o.sector(), o.strength(), o.sign(), o.excess(), "CALM", false));
		}
		Card c = ReportCardMath.card("agent-8-macro", obs, 1.0, Map.of());
		assertEquals(State.FLIPPED, c.state());
		assertTrue(c.target() < 0);
		assertEquals(0.9, c.multiplier(), 1e-9);
	}

	@Test
	void overconfidentAgentLosesTrust() {
		// Strong calls (high strength) are wrong, weak calls right → stated strength isn't useful.
		List<Obs> obs = new ArrayList<>();
		for (int i = 0; i < 80; i++) {
			boolean strong = i % 2 == 0;
			obs.add(new Obs(START.plusDays(7L * i), "S" + (i % 4), strong ? 0.9 : 0.2, 1, strong ? 1.0 : 5.0, "CALM", false));
		}
		Card c = ReportCardMath.card("agent-1-news", obs, 1.0, Map.of());
		assertFalse(c.strengthUseful());
		assertEquals(State.GOOD, c.state());
		double unpenalized = 1.0 + Math.min(1.0, c.overall().edge() * c.overall().clusters() / (c.overall().clusters() + 30.0) / 2.0);
		assertEquals(unpenalized * 0.85, c.target(), 1e-9);
	}

	@Test
	void situationBlendsTowardOverallUntilItHasEvidence() {
		List<Obs> obs = new ArrayList<>(calls(60, 3.0, 1.0, "CALM"));
		Card c = ReportCardMath.card("agent-1-news", obs, 1.0, Map.of());
		// No risk-off evidence at all → its target equals the overall target.
		assertEquals(c.target(), c.situations().get(ReportCardMath.RISK_OFF).target(), 1e-9);
	}

	@Test
	void horizonsFollowEachAgentsClock() {
		assertEquals(5, ReportCardMath.horizonFor("agent-1-news"));
		assertEquals(10, ReportCardMath.horizonFor("agent-10-technical"));
		assertEquals(20, ReportCardMath.horizonFor("agent-8-macro"));
		assertEquals(60, ReportCardMath.horizonFor("agent-12-fundamental"));
		assertEquals(60, ReportCardMath.horizonFor("agent-15-academic"));
	}
}
