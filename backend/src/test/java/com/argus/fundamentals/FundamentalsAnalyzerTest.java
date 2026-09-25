package com.argus.fundamentals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic fundamental analysis over Finnhub-shaped payloads. */
class FundamentalsAnalyzerTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static JsonNode j(String s) {
		return JSON.readTree(s);
	}

	/** One 10-Q report: end date, revenue, gross profit, operating income, net income. */
	private static String q(String end, double rev, double gp, double op, double ni) {
		return """
				{"form":"10-Q","endDate":"%s 00:00:00","report":{"ic":[
				 {"concept":"us-gaap_Revenues","value":%s},
				 {"concept":"us-gaap_GrossProfit","value":%s},
				 {"concept":"us-gaap_OperatingIncomeLoss","value":%s},
				 {"concept":"us-gaap_NetIncomeLoss","value":%s}]}}""".formatted(end, rev, gp, op, ni);
	}

	private static JsonNode financials(String... reports) {
		return j("{\"data\":[" + String.join(",", reports) + "]}");
	}

	private static JsonNode earnings(double... surprisePcts) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < surprisePcts.length; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"period\":\"2026-0").append(i + 1).append("-30\",\"actual\":1.1,\"estimate\":1.0,\"surprisePercent\":").append(surprisePcts[i]).append('}');
		}
		return j(sb.append(']').toString());
	}

	private static JsonNode recs(int sb, int b, int h, int s, int ss, int psb, int pb, int ph, int ps, int pss) {
		return j("[{\"period\":\"2026-09-01\",\"strongBuy\":" + sb + ",\"buy\":" + b + ",\"hold\":" + h + ",\"sell\":" + s + ",\"strongSell\":" + ss
				+ "},{\"period\":\"2026-08-01\",\"strongBuy\":" + psb + ",\"buy\":" + pb + ",\"hold\":" + ph + ",\"sell\":" + ps + ",\"strongSell\":" + pss + "}]");
	}

	private static final JsonNode PROFILE = j("{\"name\":\"Acme Corp\",\"marketCapitalization\":250000,\"finnhubIndustry\":\"Semiconductors\"}");

	@Test
	void aStrongGrowingProfitableCompanyScoresBullish() {
		JsonNode fin = financials(
				q("2026-07-26", 180e9, 130e9, 117e9, 100e9),
				q("2026-04-26", 150e9, 110e9, 95e9, 80e9),
				q("2025-07-27", 90e9, 60e9, 52e9, 45e9),
				q("2025-04-27", 75e9, 50e9, 40e9, 36e9));
		JsonNode metric = j("{\"peTTM\":30.0,\"forwardPE\":24.0,\"totalDebt/totalEquityQuarterly\":0.2,\"currentRatioQuarterly\":3.0,\"epsGrowthTTMYoy\":120.0}");

		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, metric, fin, earnings(8, 5, 12, 6),
				recs(30, 25, 5, 0, 0, 28, 24, 8, 0, 0), List.of("AAA", "BBB", "CCC"), List.of(45.0, 50.0, 55.0));

		assertTrue(f.applicable());
		assertTrue(f.score() >= 0.5, "score " + f.score() + " notes " + f.notes());
		assertEquals("BULLISH", f.bias());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("revenue +100.0% year-over-year")), f.notes().toString());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("beat EPS estimates in 4 of the last 4")));
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("discount")), "P/E 30 vs peer median 50 is a discount");
	}

	@Test
	void growthIsMatchedToTheSameQuarterAYearEarlierNotTheSequentialOne() {
		// Sequentially revenue fell (Q2 < Q1) but year-over-year it grew — seasonality must not read as decline.
		JsonNode fin = financials(q("2026-06-30", 110, 60, 20, 10), q("2026-03-31", 130, 70, 25, 12),
				q("2025-06-30", 100, 55, 18, 9), q("2025-03-31", 120, 65, 22, 11));

		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{}"), fin, j("[]"), j("[]"), List.of(), List.of());

		assertTrue(f.notes().stream().anyMatch(n -> n.contains("revenue +10.0% year-over-year")), f.notes().toString());
	}

	@Test
	void aShrinkingLossMakingLeveredCompanyScoresBearish() {
		JsonNode fin = financials(q("2026-06-30", 70, 20, -15, -20), q("2025-06-30", 100, 40, 10, 6));
		JsonNode metric = j("{\"peTTM\":-5.0,\"totalDebt/totalEquityQuarterly\":3.5,\"currentRatioQuarterly\":0.7}");

		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, metric, fin, earnings(-8, -5, 2, -10),
				recs(1, 3, 12, 8, 4, 2, 5, 12, 6, 3), List.of(), List.of());

		assertTrue(f.score() <= -0.4, "score " + f.score());
		assertEquals("BEARISH", f.bias());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("loss-making")));
	}

	@Test
	void anEtfWithNoCompanyDataIsNotApplicableRatherThanScored() {
		Fundamentals f = FundamentalsAnalyzer.analyze("VFV", j("{}"), j("{}"), j("{}"), j("[]"), j("[]"), List.of(), List.of());

		assertFalse(f.applicable());
		assertEquals(0.0, f.score());
		assertTrue(f.render().contains("not applicable"));
	}

	@Test
	void aBankWithNoRevenueLineFallsBackToTheGrowthRatio() {
		// Banks report no plain "Revenues" concept — growth must come from the ratio set, not be invented.
		JsonNode fin = j("{\"data\":[{\"form\":\"10-Q\",\"endDate\":\"2026-06-30 00:00:00\",\"report\":{\"ic\":["
				+ "{\"concept\":\"us-gaap_InterestAndDividendIncomeOperating\",\"value\":5000},{\"concept\":\"us-gaap_NetIncomeLoss\",\"value\":900}]}}]}");
		Fundamentals f = FundamentalsAnalyzer.analyze("BANK", PROFILE, j("{\"revenueGrowthQuarterlyYoy\":12.0}"), fin, j("[]"), j("[]"),
				List.of(), List.of());

		assertTrue(f.applicable());
		assertNull(f.quarters().get(0).revenue());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("revenue +12.0% year-over-year")), f.notes().toString());
	}

	@Test
	void analystSentimentDirectionOfChangeIsReported() {
		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"peTTM\":20.0}"), j("{}"), j("[]"),
				recs(20, 20, 5, 0, 0, 10, 15, 15, 5, 0), List.of(), List.of());

		assertNotNull(f.analysts());
		assertTrue(f.analysts().bullishShare() > f.analysts().previousBullishShare());
		assertTrue(f.notes().stream().anyMatch(n -> n.contains("improving")), f.notes().toString());
	}

	@Test
	void aRichMultipleWithoutHyperGrowthIsPenalisedAndDisclosed() {
		JsonNode fin = financials(q("2026-06-30", 105, 50, 20, 15), q("2025-06-30", 100, 48, 19, 14));

		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"peTTM\":95.0}"), fin, j("[]"), j("[]"), List.of(), List.of());

		assertTrue(f.notes().stream().anyMatch(n -> n.contains("Rich multiple")));
	}

	@Test
	void missingInputsContributeNothingAndNeverThrow() {
		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"beta\":1.2}"), null, null, null, null, null);

		assertTrue(f.applicable());
		assertEquals(0.0, f.score(), 1e-9);
		assertEquals("NEUTRAL", f.bias());
	}

	@Test
	void scoreIsBounded() {
		JsonNode fin = financials(q("2026-06-30", 1000, 900, 800, 700), q("2025-06-30", 100, 50, 30, 20));
		Fundamentals f = FundamentalsAnalyzer.analyze("ACME", PROFILE, j("{\"peTTM\":10.0,\"totalDebt/totalEquityQuarterly\":0.1,\"currentRatioQuarterly\":4}"),
				fin, earnings(50, 50, 50, 50), recs(50, 20, 0, 0, 0, 20, 20, 10, 0, 0), List.of("A", "B", "C"), List.of(60.0, 70.0, 80.0));

		assertTrue(f.score() <= 1.0 && f.score() >= -1.0);
	}
}
