package com.argus.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyUniverseServiceTest {

	private static final String CSV = """
			Symbol,Security,GICS Sector,GICS Sub-Industry,Headquarters Location,Date added,CIK,Founded
			MMM,3M,Industrials,Industrial Conglomerates,"Saint Paul, Minnesota",1957-03-04,66740,1902
			BRK.B,Berkshire Hathaway,Financials,Multi-Sector Holdings,"Omaha, Nebraska",2010-02-16,1067983,1839
			AAPL,Apple Inc.,Information Technology,Technology Hardware,"Cupertino, California",1982-11-30,320193,1977
			""";

	@Test
	void parsesSymbolsWithSectorsAndNormalisesClassShareTickers() {
		Map<String, StrategyUniverseService.Row> rows = StrategyUniverseService.parse(CSV);

		assertEquals(3, rows.size());
		assertEquals("Industrials", rows.get("MMM").sector());
		assertEquals("Technology Hardware", rows.get("AAPL").subIndustry());
		assertTrue(rows.containsKey("BRK-B"), "BRK.B must become BRK-B, the symbol the price feed understands");
	}

	@Test
	void headquartersCommasDoNotShiftTheColumns() {
		assertEquals("3M", StrategyUniverseService.parse(CSV).get("MMM").name());
		assertEquals("Financials", StrategyUniverseService.parse(CSV).get("BRK-B").sector());
	}

	@Test
	void aMalformedListYieldsNothingRatherThanAWrongUniverse() {
		assertTrue(StrategyUniverseService.parse("").isEmpty());
		assertTrue(StrategyUniverseService.parse("NotTheExpected,Header\n1,2").isEmpty());
	}
}
