package com.argus.fundamentals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.fundamentals.Fundamentals.ValuationView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The reverse DCF and the comps table: pure arithmetic, checked against known answers. */
class ValuationAnalyzerTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static JsonNode j(String s) {
		return JSON.readTree(s);
	}

	@Test
	void theSolverRecoversTheGrowthRateThePriceWasBuiltFrom() {
		double eps = 5.0, rf = 4.0, beta = 1.2;
		double r = rf / 100 + beta * ValuationAnalyzer.EQUITY_RISK_PREMIUM;
		for (double g : new double[] {0.0, 0.08, 0.15, 0.30}) {
			double price = ValuationAnalyzer.value(g, eps, r);

			ValuationView v = ValuationAnalyzer.analyze(price, eps, beta, rf, 10.0).orElseThrow();

			assertEquals(g * 100, v.impliedGrowthPct(), 0.15, "price built from " + g + " growth must solve back to it");
		}
	}

	@Test
	void aPriceThatAssumesMoreGrowthThanDeliveredIsRichAndOneThatAssumesLessIsCheap() {
		double eps = 5.0, r = 0.04 + 1.0 * ValuationAnalyzer.EQUITY_RISK_PREMIUM;
		double priceForTwentyPercent = ValuationAnalyzer.value(0.20, eps, r);
		double priceForFivePercent = ValuationAnalyzer.value(0.05, eps, r);

		assertEquals("RICH", ValuationAnalyzer.analyze(priceForTwentyPercent, eps, 1.0, 4.0, 8.0).orElseThrow().verdict(), "prices in 20%, delivered 8%");
		assertEquals("CHEAP", ValuationAnalyzer.analyze(priceForFivePercent, eps, 1.0, 4.0, 25.0).orElseThrow().verdict(), "prices in 5%, delivered 25%");
		assertEquals("FAIR", ValuationAnalyzer.analyze(priceForFivePercent, eps, 1.0, 4.0, 6.0).orElseThrow().verdict());
	}

	@Test
	void theSummaryStatesTheAssumptionsSoItCanBeChallenged() {
		double r = 0.043 + ValuationAnalyzer.EQUITY_RISK_PREMIUM;
		ValuationView v = ValuationAnalyzer.analyze(ValuationAnalyzer.value(0.12, 4.0, r), 4.0, 1.0, null, 30.0).orElseThrow();

		assertTrue(v.summary().contains("12%") && v.summary().contains("fading to 2.5% over 10 years") && v.summary().contains("9.3% discount rate"), v.summary());
		assertEquals(9.3, v.discountRatePct(), 1e-9);
		assertEquals(-18.0, v.gapPts(), 0.2);
	}

	@Test
	void withoutDeliveredGrowthNoRichOrCheapCallIsMade() {
		ValuationView v = ValuationAnalyzer.analyze(100.0, 5.0, 1.0, 4.0, null).orElseThrow();

		assertEquals("FAIR", v.verdict());
		assertNull(v.gapPts());
		assertTrue(v.summary().contains("no rich/cheap call"));
	}

	@Test
	void noPositiveEarningsOrPriceMeansNoValuation() {
		assertTrue(ValuationAnalyzer.analyze(100.0, -2.0, 1.0, 4.0, 10.0).isEmpty());
		assertTrue(ValuationAnalyzer.analyze(100.0, 0.0, 1.0, 4.0, 10.0).isEmpty());
		assertTrue(ValuationAnalyzer.analyze(null, 5.0, 1.0, 4.0, 10.0).isEmpty());
		assertTrue(ValuationAnalyzer.analyze(0.0, 5.0, 1.0, 4.0, 10.0).isEmpty());
	}

	@Test
	void extremePricesAreBoundedAndSaidToBeBeyondTheRange() {
		ValuationView wild = ValuationAnalyzer.analyze(100_000.0, 1.0, 1.0, 4.0, 10.0).orElseThrow();
		assertEquals(80.0, wild.impliedGrowthPct());
		assertTrue(wild.summary().contains("over 80%"));

		ValuationView cheap = ValuationAnalyzer.analyze(0.5, 5.0, 1.0, 4.0, 10.0).orElseThrow();
		assertEquals(-20.0, cheap.impliedGrowthPct());
		assertTrue(cheap.summary().contains("under 20%"));
	}

	@Test
	void betaIsClampedAndRaisesTheDiscountRate() {
		assertEquals(4.0 + 1.8 * 5, ValuationAnalyzer.analyze(100.0, 5.0, 9.0, 4.0, 10.0).orElseThrow().discountRatePct(), 0.1, "beta capped at 1.8");
		assertEquals(4.0 + 0.7 * 5, ValuationAnalyzer.analyze(100.0, 5.0, 0.1, 4.0, 10.0).orElseThrow().discountRatePct(), 0.1, "beta floored at 0.7");
	}

	// ---- integrated into the fundamentals analysis ----

	private static final JsonNode PROFILE = j("{\"name\":\"Acme\",\"marketCapitalization\":1000,\"finnhubIndustry\":\"Tech\"}");

	private static List<FundamentalsAnalyzer.PeerMetric> peers() {
		return List.of(
				new FundamentalsAnalyzer.PeerMetric("P1", j("{\"peTTM\":20,\"psTTM\":3,\"evEbitdaTTM\":12,\"revenueGrowthTTMYoy\":15}")),
				new FundamentalsAnalyzer.PeerMetric("P2", j("{\"peTTM\":24,\"psTTM\":4,\"evEbitdaTTM\":14,\"revenueGrowthTTMYoy\":12}")),
				new FundamentalsAnalyzer.PeerMetric("P3", j("{\"peTTM\":30,\"psTTM\":5,\"evEbitdaTTM\":16,\"revenueGrowthTTMYoy\":9}")));
	}

	@Test
	void theCompsTableComparesEveryMultipleAgainstThePeerMedian() {
		JsonNode metric = j("{\"peTTM\":48,\"psTTM\":10,\"evEbitdaTTM\":28,\"revenueGrowthTTMYoy\":6}");

		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, metric, j("{}"), j("[]"), j("[]"), peers(), null, null);

		assertNotNull(f.peers());
		assertEquals(3, f.peers().rows().size());
		assertEquals(24.0, f.peers().medianPe(), 1e-9);
		assertEquals(100.0, f.peers().premiumPct(), 1e-9, "P/E 48 vs median 24");
		assertEquals(4.0, f.peers().medianPs(), 1e-9);
		assertEquals(150.0, f.peers().psPremiumPct(), 1e-9, "P/S 10 vs median 4");
		assertEquals(14.0, f.peers().medianEvEbitda(), 1e-9);
		assertTrue(f.notes().stream().anyMatch(n -> n.startsWith("Comps: P/S 10.0 vs 4.0 (+150%)")), f.notes().toString());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("sales multiple carries a big premium")), "premium without faster growth is called out");
	}

	@Test
	void theReverseDcfAppearsInTheAnalysisAndPenalisesARichStock() {
		double r = 0.043 + 1.2 * ValuationAnalyzer.EQUITY_RISK_PREMIUM;
		double price = ValuationAnalyzer.value(0.25, 4.0, r); // price assumes 25% growth
		JsonNode rich = j("{\"epsTTM\":4.0,\"beta\":1.2,\"epsGrowth5Y\":6,\"epsGrowthTTMYoy\":8}");
		JsonNode fair = j("{\"epsTTM\":4.0,\"beta\":1.2,\"epsGrowth5Y\":24,\"epsGrowthTTMYoy\":26}");

		Fundamentals fRich = FundamentalsAnalyzer.analyze("ACME", PROFILE, rich, j("{}"), j("[]"), j("[]"), List.of(), price, null);
		Fundamentals fFair = FundamentalsAnalyzer.analyze("ACME", PROFILE, fair, j("{}"), j("[]"), j("[]"), List.of(), price, null);

		assertNotNull(fRich.valuation());
		assertEquals("RICH", fRich.valuation().verdict());
		assertEquals("FAIR", fFair.valuation().verdict());
		assertTrue(fRich.notes().stream().anyMatch(n -> n.startsWith("Reverse DCF:")));
		assertTrue(fRich.score() < fFair.score(), "an already-priced-in story must score worse than the same price backed by real growth");
	}

	@Test
	void withoutAPriceThereIsNoReverseDcfAndNothingBreaks() {
		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"epsTTM\":4.0}"), j("{}"), j("[]"), j("[]"), List.of(), null, null);

		assertNull(f.valuation());
	}

	@Test
	void theLegacyOverloadStillWorks() {
		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"peTTM\":30.0}"), j("{}"), j("[]"), j("[]"), List.of("A", "B", "C"),
				List.of(45.0, 50.0, 55.0));

		assertEquals(50.0, f.peers().medianPe(), 1e-9);
		assertEquals(3, f.peers().peers().size());
	}

	@Test
	void anOldStoredSnapshotWithoutTheNewFieldsStillDeserialises() throws Exception {
		// Snapshots written before valuation existed have no "valuation" / comps rows — they must still load.
		String old = "{\"ticker\":\"OLD\",\"applicable\":true,\"name\":\"Old\",\"industry\":null,\"marketCapMillions\":1.0,\"ratios\":{},\"quarters\":[],"
				+ "\"earnings\":[],\"analysts\":null,\"peers\":{\"peers\":[\"A\"],\"medianPe\":20.0,\"pe\":30.0,\"premiumPct\":50.0},\"score\":0.1,"
				+ "\"bias\":\"NEUTRAL\",\"notes\":[\"n\"],\"fetchedAt\":\"2026-09-01T00:00:00Z\"}";

		Fundamentals f = JSON.readValue(old, Fundamentals.class);

		assertEquals("OLD", f.ticker());
		assertNull(f.valuation());
		assertEquals(50.0, f.peers().premiumPct());
		Optional<Object> none = Optional.empty();
		assertTrue(none.isEmpty());
	}
}
