package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListingResolverTest {

	private final ListingResolver resolver = new ListingResolver(" vfv, DOL ,ZGLD");

	@Test
	void declaredTsxTickersAlwaysResolveToTheTorontoListingOnly() {
		assertTrue(resolver.isTsx("dol"));
		assertEquals(List.of("DOL.TO"), resolver.yahooSymbols("DOL", false));
		assertEquals(List.of("VFV.TO"), resolver.yahooSymbols("VFV", true));
	}

	@Test
	void undeclaredTickersUseTheBareSymbolUnlessQuotedInCad() {
		assertEquals(List.of("AAPL"), resolver.yahooSymbols("AAPL", false));
		assertEquals(List.of("XYZ.TO", "XYZ"), resolver.yahooSymbols("XYZ", true));
	}
}
