package com.argus.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.portfolio.AccountMetaRepository;
import com.argus.portfolio.PositionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Grounding blends account-derived facts with the persisted, user-editable profile (Story 7.6). */
class InvestorProfileServiceTest {

	private final AccountMetaRepository accountMeta = mock(AccountMetaRepository.class);
	private final PositionRepository positions = mock(PositionRepository.class);
	private final InvestorProfileRepository profiles = mock(InvestorProfileRepository.class);
	// Config defaults mirror the @Value fallbacks.
	private final InvestorProfileService service =
			new InvestorProfileService(accountMeta, positions, profiles, "Canadian", "CAD");

	@Test
	void blankProfileFallsBackToConfigDefaults() {
		// No saved profile, no accounts → Mockito returns empty collections / Optional.empty by default.
		String out = service.describe();

		assertEquals("Canadian investor; home currency CAD.", out,
				"a blank profile reproduces the pre-7.6 config-default sentence");
	}

	@Test
	void savedProfileFieldsAppearInDescribe() {
		InvestorProfile p = new InvestorProfile();
		p.setRiskTolerance(RiskTolerance.GROWTH);
		p.setFinancialGoal("Retire by 55");
		p.setTargetAmount(new BigDecimal("2000000"));
		p.setTargetDate(LocalDate.of(2040, 1, 1));
		p.setNotes("Prefers low-turnover, tax-efficient ETFs");
		when(profiles.findSingleton()).thenReturn(Optional.of(p));

		String out = service.describe();

		assertTrue(out.contains("Risk tolerance: Growth."), out);
		assertTrue(out.contains("Goal: Retire by 55."), out);
		assertTrue(out.contains("Target: CAD 2000000 by 2040-01-01."), out);
		assertTrue(out.contains("Preferences: Prefers low-turnover, tax-efficient ETFs."), out);
	}

	@Test
	void profileResidencyAndCurrencyOverrideConfig() {
		InvestorProfile p = new InvestorProfile();
		p.setResidency("American");
		p.setHomeCurrency("usd"); // stored lower-case; normalized on read
		when(profiles.findSingleton()).thenReturn(Optional.of(p));

		String out = service.describe();

		assertTrue(out.startsWith("American investor; home currency USD."), out);
		assertEquals("American", service.residency());
		assertEquals("USD", service.homeCurrency());
	}

	@Test
	void accessorsFallBackToConfigWhenUnset() {
		assertEquals("Canadian", service.residency());
		assertEquals("CAD", service.homeCurrency());
	}

	@Test
	void emptyProfileDoesNotEmitProfileClauses() {
		String out = service.describe();

		assertFalse(out.contains("Risk tolerance"), "no risk clause when unset");
		assertFalse(out.contains("Goal:"), "no goal clause when unset");
		assertFalse(out.contains("Target:"), "no target clause when unset");
	}
}
