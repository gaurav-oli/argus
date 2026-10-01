package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Parsing the published corpus: the fields that decide whether a strategy is trusted must survive intact. */
class StrategyDocImporterTest {

	private static final String HEADER = "Acronym,Cat.Signal,Predictability in OP,Authors,Year,LongDescription,Journal,"
			+ "Cat.Data,Cat.Economic,SampleStartYear,SampleEndYear,Sign,Return,T-Stat,Stock Weight,LS Quantile,"
			+ "Portfolio Period,Detailed Definition,GScholarCites202509";

	@Test
	void readsAPredictorWithItsPaperEffectSizeAndRules() {
		String csv = HEADER + "\nMom12m,Predictor,1_clear,Jegadeesh and Titman,1993,Momentum (12 month),JF,Price,momentum,"
				+ "1965,1989,1.0,1.31,3.74,EW,0.1,3.0,\"Stock return between months t-12 and t-1\",9000";

		List<StrategyDocImporter.Row> rows = StrategyDocImporter.parse(csv);

		assertEquals(1, rows.size());
		StrategyDocImporter.Row r = rows.get(0);
		assertEquals("Mom12m", r.acronym());
		assertEquals("Momentum (12 month)", r.name());
		assertEquals("Jegadeesh and Titman", r.authors());
		assertEquals(1993, r.year());
		assertEquals("JF", r.journal());
		assertEquals(AcademicStrategy.Kind.PREDICTOR, r.kind());
		assertEquals("Price", r.dataCategory());
		assertEquals(0, r.tStat().compareTo(new java.math.BigDecimal("3.74")));
		assertEquals(0, r.sign().compareTo(new java.math.BigDecimal("1.0")));
		assertEquals(0, r.lsQuantile().compareTo(new java.math.BigDecimal("0.1")));
		assertEquals(0, r.rebalanceMonths().compareTo(new java.math.BigDecimal("3.0")));
		assertEquals("Stock return between months t-12 and t-1", r.definition());
		assertEquals("1_clear", r.replicationGrade());
		assertEquals(9000, r.cites());
	}

	@Test
	void flagsPlaceboSignalsSoTheyAreNeverTradedAsPredictors() {
		String csv = HEADER + "\nSomeDud,Placebo,4_not,Author,2010,A signal that does not work,JF,Accounting,other,"
				+ "1990,2005,1.0,0.1,0.4,EW,0.1,1.0,\"Something\",5";

		assertEquals(AcademicStrategy.Kind.PLACEBO, StrategyDocImporter.parse(csv).get(0).kind());
	}

	@Test
	void survivesMissingNumbersAndRowsWithoutAnAcronym() {
		String csv = HEADER + "\nNoNums,Predictor,indirect,A,2000,Thing,JF,Price,x,,,,,,EW,,,\"Def\",\n"
				+ ",Predictor,1_clear,B,2001,Nameless,JF,Price,x,,,,,,EW,,,\"Def\",";

		List<StrategyDocImporter.Row> rows = StrategyDocImporter.parse(csv);

		assertEquals(1, rows.size(), "the row with no acronym is skipped, not guessed at");
		assertNull(rows.get(0).tStat(), "a missing t-stat stays null rather than becoming zero");
		assertNull(rows.get(0).sign());
		assertNull(rows.get(0).lsQuantile());
		assertEquals(2000, rows.get(0).year(), "the fields that are present are still read");
	}

	@Test
	void definitionsContainingCommasAndQuotesStayWhole() {
		String csv = HEADER + "\nAmihud,Predictor,1_clear,Amihud,2002,Illiquidity,JFM,Trading,liquidity,1964,1997,"
				+ "1.0,,6.6,EW,,12.0,\"Past twelve month average of: daily return (abs(ret)) divided by turnover, "
				+ "where turnover is (abs(prc)*vol)\",4000";

		String def = StrategyDocImporter.parse(csv).get(0).definition();

		assertTrue(def.contains("divided by turnover, where turnover"), def);
	}

	@Test
	void anEmptyOrHeaderOnlyDocumentYieldsNothingRatherThanThrowing() {
		assertTrue(StrategyDocImporter.parse("").isEmpty());
		assertTrue(StrategyDocImporter.parse(HEADER).isEmpty());
	}
}
