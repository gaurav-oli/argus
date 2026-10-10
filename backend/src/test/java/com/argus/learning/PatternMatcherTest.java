package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PatternMatcherTest {

	private static final Set<String> SETUP = Set.of("dir=BULLISH", "sector=TECH", "regime=RISK_OFF", "lead=NEWS",
			"trend=DOWNTREND", "conv=70-79", "hold=30");

	private static List<PatternMatcher.PastTrade> similar(int wins, int losses, String lossExit) {
		List<PatternMatcher.PastTrade> out = new ArrayList<>();
		long id = 1;
		for (int i = 0; i < wins; i++) {
			out.add(new PatternMatcher.PastTrade(id++, Set.of("dir=BULLISH", "sector=TECH", "regime=RISK_OFF", "lead=NEWS",
					"trend=DOWNTREND", "conv=62-69", "hold=7"), true, new BigDecimal("3"), "HORIZON"));
		}
		for (int i = 0; i < losses; i++) {
			out.add(new PatternMatcher.PastTrade(id++, Set.of("dir=BULLISH", "sector=TECH", "regime=RISK_OFF", "lead=NEWS",
					"trend=DOWNTREND", "conv=70-79"), false, new BigDecimal("-4"), lossExit));
		}
		return out;
	}

	@Test
	void anEmptyLibraryFailsOpen() {
		PatternAdvice a = PatternMatcher.advise(SETUP, List.of());
		assertEquals("NO_PATTERN", a.action());
		assertEquals(1.0, a.sizeMultiplier());
		assertEquals(1.0, a.stopKeep());
		assertTrue(a.note().startsWith("No prior pattern"));
	}

	@Test
	void tooFewMatchesIsNoPattern() {
		PatternAdvice a = PatternMatcher.advise(SETUP, similar(1, 3, "HORIZON"));
		assertEquals("NO_PATTERN", a.action());
		assertEquals(4, a.matches());
	}

	@Test
	void dissimilarTradesDoNotMatch() {
		List<PatternMatcher.PastTrade> other = new ArrayList<>();
		for (long i = 1; i <= 10; i++) {
			other.add(new PatternMatcher.PastTrade(i, Set.of("dir=BULLISH", "sector=ENERGY", "regime=RISK_ON", "lead=MACRO",
					"trend=UPTREND", "conv=80+"), false, BigDecimal.ONE, "STOP"));
		}
		assertEquals("NO_PATTERN", PatternMatcher.advise(SETUP, other).action());
	}

	@Test
	void aMostlyLosingPatternIsSkipped() {
		PatternAdvice a = PatternMatcher.advise(SETUP, similar(2, 7, "HORIZON"));
		assertEquals("SKIP", a.action());
		assertEquals(0, a.sizeMultiplier());
		assertEquals(9, a.matches());
		assertEquals(22, a.winRatePct());
		assertTrue(a.note().contains("→ skipped"), a.note());
	}

	@Test
	void aWeakPatternSizesDown() {
		PatternAdvice a = PatternMatcher.advise(SETUP, similar(2, 4, "HORIZON"));
		assertEquals("SIZE_DOWN", a.action());
		assertEquals(0.5, a.sizeMultiplier());
		assertEquals(1.0, a.stopKeep());
		assertTrue(a.note().endsWith("→ half size."), a.note());
	}

	@Test
	void aPatternThatStopsOutTightensTheStop() {
		PatternAdvice a = PatternMatcher.advise(SETUP, similar(5, 5, "STOP"));
		assertEquals("TIGHTEN_STOP", a.action());
		assertEquals(1.0, a.sizeMultiplier());
		assertEquals(0.7, a.stopKeep());
		assertEquals(50, a.stopOutPct());
	}

	@Test
	void aWinningPatternProceedsAndNamesWhatTheMatchesShare() {
		PatternAdvice a = PatternMatcher.advise(SETUP, similar(6, 2, "HORIZON"));
		assertEquals("PROCEED", a.action());
		assertEquals(75, a.winRatePct());
		assertEquals(0, a.avgReturnPct().compareTo(new BigDecimal("1.2500")));
		assertEquals("lead=NEWS · regime=RISK_OFF · sector=TECH · trend=DOWNTREND", a.pattern());
		assertTrue(a.note().contains("avg +1.3%"), a.note());
		assertTrue(a.note().endsWith("→ proceeded as planned."), a.note());
	}

	@Test
	void horizonTokensAreIgnored() {
		assertEquals(1.0, PatternMatcher.jaccard(PatternMatcher.comparable(Set.of("a", "hold=7")),
				PatternMatcher.comparable(Set.of("a", "hold=90"))));
	}
}
