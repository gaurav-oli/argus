package com.argus.persona;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.conversation.InvestorProfileService;
import com.argus.marketdata.FxRateService;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The deterministic Canadian-lens facts that ground the Canadian persona (Stories 7.5 + 7.6). */
class CanadianContextServiceTest {

	private final FxRateService fx = mock(FxRateService.class);
	private final LivePortfolioService livePortfolio = mock(LivePortfolioService.class);
	private final InvestorProfileService investorProfile = mock(InvestorProfileService.class);
	private final CanadianContextService service =
			new CanadianContextService(fx, livePortfolio, investorProfile);

	{
		// Default: a Canadian investor with a CAD home currency (Story 7.6 profile defaults).
		when(investorProfile.residency()).thenReturn("Canadian");
		when(investorProfile.homeCurrency()).thenReturn("CAD");
	}

	@Test
	void usListedNameGetsCadEquivalentAndWithholdingNote() {
		when(livePortfolio.priceCurrency("NVDA")).thenReturn(Optional.of("USD"));
		when(fx.usdCadOn(any(LocalDate.class))).thenReturn(Optional.of(new BigDecimal("1.4200")));

		String facts = service.describe("NVDA", new BigDecimal("200.00"));

		assertTrue(facts.contains("USD/CAD"), "states the live rate");
		assertTrue(facts.contains("C$284.00"), "converts the US$200 price target at 1.42 → C$284.00");
		assertTrue(facts.contains("15%") && facts.contains("RRSP") && facts.contains("TFSA"),
				"includes the withholding + registered-account note");
	}

	@Test
	void canadianListedNameSkipsWithholdingAndFx() {
		when(livePortfolio.priceCurrency("XQQ")).thenReturn(Optional.of("CAD"));

		String facts = service.describe("XQQ", new BigDecimal("120.00"));

		assertTrue(facts.contains("Canadian-listed"), "flags it as CAD-listed");
		assertTrue(facts.contains("no US dividend withholding"), "no US withholding for a CAD name");
		assertFalse(facts.contains("USD/CAD"), "no FX conversion line for a CAD name");
	}

	@Test
	void unpricedNameDefaultsToUsListedWithholdingNote() {
		when(livePortfolio.priceCurrency("SPCX")).thenReturn(Optional.empty());
		when(fx.usdCadOn(any(LocalDate.class))).thenReturn(Optional.of(new BigDecimal("1.4200")));

		String facts = service.describe("SPCX", null);

		assertTrue(facts.contains("US-listed"), "unpriced → defaults to the US-listed withholding note");
		assertTrue(facts.contains("15%"), "still surfaces the withholding fact");
	}

	@Test
	void missingFxStillReturnsTheTaxNote() {
		when(livePortfolio.priceCurrency("AAPL")).thenReturn(Optional.of("USD"));
		when(fx.usdCadOn(any(LocalDate.class))).thenReturn(Optional.empty());

		String facts = service.describe("AAPL", new BigDecimal("180.00"));

		assertFalse(facts.contains("USD/CAD"), "no rate line when the rate is unavailable");
		assertTrue(facts.contains("15%") && facts.contains("RRSP"), "tax facts are still present");
	}

	@Test
	void nonCanadianResidencySuppressesTheCanadianLens() {
		when(investorProfile.residency()).thenReturn("American");
		when(livePortfolio.priceCurrency("NVDA")).thenReturn(Optional.of("USD"));

		String facts = service.describe("NVDA", new BigDecimal("200.00"));

		assertTrue(facts.contains("American"), "names the actual residency");
		assertTrue(facts.contains("does not apply"), "the Canadian lens is suppressed");
		assertFalse(facts.contains("15%"), "no US-withholding facts applied for a non-Canadian investor");
		assertFalse(facts.contains("eligible in"), "no registered-account eligibility framing applied");
	}

	@Test
	void nonCadHomeCurrencyDropsTheFxLineButKeepsEligibility() {
		when(investorProfile.homeCurrency()).thenReturn("USD");
		when(livePortfolio.priceCurrency("NVDA")).thenReturn(Optional.of("USD"));
		when(fx.usdCadOn(any(LocalDate.class))).thenReturn(Optional.of(new BigDecimal("1.4200")));

		String facts = service.describe("NVDA", new BigDecimal("200.00"));

		assertFalse(facts.contains("USD/CAD"), "no CAD conversion when the home currency isn't CAD");
		assertTrue(facts.contains("15%"), "still a Canadian resident → withholding note applies");
	}
}
