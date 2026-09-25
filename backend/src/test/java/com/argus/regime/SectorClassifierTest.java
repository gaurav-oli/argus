package com.argus.regime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.marketdata.FinnhubRest;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SectorClassifierTest {

	private final FinnhubRest finnhub = mock(FinnhubRest.class);
	private final SectorClassifier classifier = new SectorClassifier(finnhub, "key");

	@Test
	void curatedTickersNeedNoLookup() {
		assertEquals(Sector.SEMICONDUCTORS, classifier.sectorOf("nvda"));
		assertEquals(Sector.GOLD, classifier.sectorOf("ZGLD"));
		assertEquals(Sector.CANADIAN_EQUITY, classifier.sectorOf("VDY"));
		verify(finnhub, times(0)).get(anyString());
	}

	@Test
	void unknownTickersUseFinnhubIndustryAndAreCached() {
		when(finnhub.get(anyString())).thenReturn(Optional.of("{\"finnhubIndustry\":\"Oil & Gas\"}"));

		assertEquals(Sector.ENERGY, classifier.sectorOf("XOM"));
		assertEquals(Sector.ENERGY, classifier.sectorOf("XOM"));

		verify(finnhub, times(1)).get(anyString());
	}

	@Test
	void aFailedLookupIsOtherAndNotCached() {
		when(finnhub.get(anyString())).thenReturn(Optional.empty(), Optional.of("{\"finnhubIndustry\":\"Banking\"}"));

		assertEquals(Sector.OTHER, classifier.sectorOf("JPM"));
		assertEquals(Sector.FINANCIALS, classifier.sectorOf("JPM"), "a transient failure must be able to heal");
	}

	@Test
	void industryStringsMapToSectors() {
		assertEquals(Sector.SEMICONDUCTORS, SectorClassifier.fromIndustry("Semiconductors"));
		assertEquals(Sector.HEALTHCARE, SectorClassifier.fromIndustry("Biotechnology"));
		assertEquals(Sector.UTILITIES, SectorClassifier.fromIndustry("Utilities"));
		assertEquals(Sector.OTHER, SectorClassifier.fromIndustry(""));
	}
}
