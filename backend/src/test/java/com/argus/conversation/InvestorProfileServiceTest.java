package com.argus.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.portfolio.AccountMetaRepository;
import com.argus.portfolio.PositionRepository;
import com.argus.security.CurrentUserContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Grounding blends account-derived facts with the persisted, user-editable profile (Story 7.6), now
 * scoped to whichever person is signed in ({@link CurrentUserContext}, Phase 2). */
class InvestorProfileServiceTest {

	private static final Long ME = 1L;

	private final AccountMetaRepository accountMeta = mock(AccountMetaRepository.class);
	private final PositionRepository positions = mock(PositionRepository.class);
	private final InvestorProfileRepository profiles = mock(InvestorProfileRepository.class);
	// Config defaults mirror the @Value fallbacks.
	private final InvestorProfileService service =
			new InvestorProfileService(accountMeta, positions, profiles, "Canadian", "CAD");

	@Test
	void blankProfileFallsBackToConfigDefaults() {
		// No saved profile, no accounts → Mockito returns empty collections / Optional.empty by default.
		String out = CurrentUserContext.callAs(ME, service::describe);

		assertEquals("Canadian investor; home currency CAD.", out,
				"a blank profile reproduces the pre-7.6 config-default sentence");
	}

	@Test
	void savedProfileFieldsAppearInDescribe() {
		InvestorProfile p = new InvestorProfile(ME);
		p.setRiskTolerance(RiskTolerance.GROWTH);
		p.setTradingHorizon(TradingHorizon.LONG_TERM_HOLDER);
		p.setFinancialGoal("Retire by 55");
		p.setTargetAmount(new BigDecimal("2000000"));
		p.setTargetDate(LocalDate.of(2040, 1, 1));
		p.setNotes("Prefers low-turnover, tax-efficient ETFs");
		when(profiles.findById(ME)).thenReturn(Optional.of(p));

		String out = CurrentUserContext.callAs(ME, service::describe);

		assertTrue(out.contains("Risk tolerance: Growth."), out);
		assertTrue(out.contains("Prefers to be a long-term holder."), out);
		assertTrue(out.contains("Goal: Retire by 55."), out);
		assertTrue(out.contains("Target: CAD 2000000 by 2040-01-01."), out);
		assertTrue(out.contains("Preferences: Prefers low-turnover, tax-efficient ETFs."), out);
	}

	@Test
	void profileResidencyAndCurrencyOverrideConfig() {
		InvestorProfile p = new InvestorProfile(ME);
		p.setResidency("American");
		p.setHomeCurrency("usd"); // stored lower-case; normalized on read
		when(profiles.findById(ME)).thenReturn(Optional.of(p));

		String out = CurrentUserContext.callAs(ME, service::describe);

		assertTrue(out.startsWith("American investor; home currency USD."), out);
		assertEquals("American", CurrentUserContext.callAs(ME, service::residency));
		assertEquals("USD", CurrentUserContext.callAs(ME, service::homeCurrency));
	}

	@Test
	void accessorsFallBackToConfigWhenUnset() {
		assertEquals("Canadian", CurrentUserContext.callAs(ME, service::residency));
		assertEquals("CAD", CurrentUserContext.callAs(ME, service::homeCurrency));
	}

	@Test
	void emptyProfileDoesNotEmitProfileClauses() {
		String out = CurrentUserContext.callAs(ME, service::describe);

		assertFalse(out.contains("Risk tolerance"), "no risk clause when unset");
		assertFalse(out.contains("Goal:"), "no goal clause when unset");
		assertFalse(out.contains("Target:"), "no target clause when unset");
	}

	@Test
	void noProfileYetMeansOnboardingIsStillNeeded() {
		when(profiles.findById(any())).thenReturn(Optional.empty());

		assertTrue(CurrentUserContext.callAs(ME, service::needsOnboarding));
	}

	@Test
	void noSignedInUserAlsoDegradesToNeedingOnboardingNeverACrash() {
		// No CurrentUserContext.callAs wrapper here at all — simulates a stray unauthenticated call.
		assertTrue(service.needsOnboarding());
	}

	@Test
	void savingAProfileMarksOnboardingDone() {
		when(profiles.findById(any())).thenReturn(Optional.empty());
		when(profiles.save(org.mockito.ArgumentMatchers.any(InvestorProfile.class)))
				.thenAnswer(inv -> inv.getArgument(0));

		InvestorProfile saved = CurrentUserContext.callAs(ME, () -> service.save(RiskTolerance.BALANCED,
				TradingHorizon.ACTIVE_TRADER, null, null, null, null, null, null));

		assertFalse(saved.getOnboardingCompletedAt() == null, "saving for the first time completes onboarding");
	}

	@Test
	void savingWithNoSignedInUserIsRejectedNotACrash() {
		assertThrowsUnauthorized(() -> service.save(RiskTolerance.BALANCED, null, null, null, null, null, null, null));
	}

	@Test
	void skippingMarksOnboardingDoneWithoutSettingAnyAnswers() {
		when(profiles.findById(any())).thenReturn(Optional.empty());
		when(profiles.save(org.mockito.ArgumentMatchers.any(InvestorProfile.class)))
				.thenAnswer(inv -> inv.getArgument(0));

		InvestorProfile saved = CurrentUserContext.callAs(ME, service::skipOnboarding);

		assertFalse(saved.getOnboardingCompletedAt() == null);
		assertEquals(null, saved.getRiskTolerance());
	}

	private static void assertThrowsUnauthorized(Runnable action) {
		org.junit.jupiter.api.Assertions.assertThrows(com.argus.common.UnauthorizedException.class, action::run);
	}
}
