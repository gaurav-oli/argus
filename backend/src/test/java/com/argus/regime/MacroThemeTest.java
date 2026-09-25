package com.argus.regime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Macro-theme classification and the sign-aware per-sector exposure that replaced "one number for everyone". */
class MacroThemeTest {

	@Test
	void classifiesTheHeadlineIntoItsThemes() {
		assertTrue(MacroTheme.classify("Treasury yields hit 19-year high on rate hike bets")
				.contains(MacroTheme.RATES_YIELDS));
		assertTrue(MacroTheme.classify("Trump threatens new tariffs on Chinese chips")
				.containsAll(List.of(MacroTheme.TRADE_TARIFFS, MacroTheme.FISCAL_POLITICS)));
		assertTrue(MacroTheme.classify("OPEC cuts output as Strait of Hormuz tensions rise")
				.contains(MacroTheme.ENERGY_OIL));
	}

	@Test
	void unmatchedTextFallsBackToGeneral() {
		assertEquals(List.of(MacroTheme.GENERAL), MacroTheme.classify("Something entirely unrelated happened"));
		assertEquals(List.of(MacroTheme.GENERAL), MacroTheme.classify(null));
	}

	@Test
	void matchingIsWholeWordNotSubstring() {
		// "oil" must not fire on "spoiled"/"boiling"; "war" must not fire on "warehouse"/"software".
		assertEquals(List.of(MacroTheme.GENERAL), MacroTheme.classify("Boiling summer spoiled the warehouse software rollout"));
	}

	@Test
	void ratesHitGrowthMuchMoreThanBanksAndBanksMoveTheOtherWay() {
		assertTrue(MacroTheme.RATES_YIELDS.sensitivity(Sector.AI_INFRA) > 0.8);
		assertTrue(MacroTheme.RATES_YIELDS.sensitivity(Sector.REAL_ESTATE) > 0.8);
		assertTrue(MacroTheme.RATES_YIELDS.sensitivity(Sector.FINANCIALS) < 0, "banks benefit from higher yields");
	}

	@Test
	void oilShocksHelpEnergyAndGold() {
		assertTrue(MacroTheme.ENERGY_OIL.sensitivity(Sector.ENERGY) < 0);
		assertTrue(MacroTheme.GEOPOLITICS.sensitivity(Sector.GOLD) < 0);
		assertTrue(MacroTheme.GEOPOLITICS.sensitivity(Sector.INDUSTRIALS_DEFENSE) < 0, "defense benefits from conflict");
	}

	@Test
	void tariffsHitSemisAndAutosFarMoreThanUtilities() {
		assertTrue(MacroTheme.TRADE_TARIFFS.sensitivity(Sector.SEMICONDUCTORS)
				> MacroTheme.TRADE_TARIFFS.sensitivity(Sector.UTILITIES) + 0.5);
		assertTrue(MacroTheme.TRADE_TARIFFS.sensitivity(Sector.AUTOS_EV) > 0.8);
	}

	@Test
	void meanSensitivityAveragesAcrossMatchedThemes() {
		double mean = MacroTheme.meanSensitivity(List.of(MacroTheme.ENERGY_OIL, MacroTheme.GEOPOLITICS), Sector.ENERGY);
		assertEquals((-0.8 + -0.5) / 2, mean, 1e-9);
	}
}
