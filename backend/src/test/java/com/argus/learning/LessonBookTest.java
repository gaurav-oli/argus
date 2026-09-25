package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LessonBookTest {

	private final LearnedRuleRepository repo = mock(LearnedRuleRepository.class);
	private final LessonBook book = new LessonBook(repo);

	private static LearnedRule rule(LearnedRule.Kind kind, double effect, String description, String... predicates) {
		LearnedRule r = new LearnedRule(kind, Set.of(predicates), description, effect);
		r.recordEvidence(20, 14, 0.33, -3.1, 5, -2.4);
		r.activate("replicated");
		return r;
	}

	private void withRules(LearnedRule... rules) {
		when(repo.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE)).thenReturn(List.of(rules));
		book.reload();
	}

	@Test
	void noRulesMeansNoEffect() {
		withRules();

		assertTrue(book.evaluate(Set.of("dir=BULLISH", "sector=ENERGY")).isNone());
	}

	@Test
	void aRuleAppliesOnlyWhenAllItsPredicatesArePresent() {
		withRules(rule(LearnedRule.Kind.PENALTY, 8, "Bullish social-led calls lose", "dir=BULLISH", "lead=SOCIAL"));

		assertEquals(-8, book.evaluate(Set.of("dir=BULLISH", "lead=SOCIAL", "sector=TECH")).scoreDelta());
		assertTrue(book.evaluate(Set.of("dir=BULLISH", "lead=NEWS")).isNone(), "one predicate missing → no effect");
		assertTrue(book.evaluate(Set.of()).isNone());
	}

	@Test
	void penaltiesBoostsBlocksCapsAndSizesCombine() {
		withRules(rule(LearnedRule.Kind.PENALTY, 6, "p", "dir=BULLISH"),
				rule(LearnedRule.Kind.BOOST, 4, "b", "sector=ENERGY"),
				rule(LearnedRule.Kind.CAP_HOLD, 7, "cap", "regime=RISK_OFF"),
				rule(LearnedRule.Kind.SIZE, 0.5, "size", "sector=ENERGY"),
				rule(LearnedRule.Kind.BLOCK, 0, "Never buy X in a selloff", "regime=RISK_OFF", "dir=BULLISH"));

		LessonEffect e = book.evaluate(Set.of("dir=BULLISH", "sector=ENERGY", "regime=RISK_OFF"));

		assertEquals(-2, e.scoreDelta(), "-6 + 4");
		assertEquals("Never buy X in a selloff", e.blockReason());
		assertEquals(7, e.holdCapDays());
		assertEquals(0.5, e.sizeMultiplier(), 1e-9);
		assertEquals(5, e.applied().size());
	}

	@Test
	void theScoreAdjustmentIsClampedSoAPileOfRulesCannotDistortADecision() {
		withRules(rule(LearnedRule.Kind.PENALTY, 15, "a", "dir=BULLISH"), rule(LearnedRule.Kind.PENALTY, 15, "b", "dir=BULLISH"),
				rule(LearnedRule.Kind.BOOST, 20, "c", "sector=TECH"), rule(LearnedRule.Kind.BOOST, 20, "d", "sector=TECH"));

		assertEquals(-25 + 0, book.evaluate(Set.of("dir=BULLISH")).scoreDelta(), "penalties clamp at -25");
		assertEquals(10, book.evaluate(Set.of("sector=TECH")).scoreDelta(), "boosts clamp at +10 — warnings outweigh encouragement");
	}

	@Test
	void theSizeMultiplierIsClamped() {
		withRules(rule(LearnedRule.Kind.SIZE, 0.2, "a", "dir=BULLISH"), rule(LearnedRule.Kind.SIZE, 0.2, "b", "dir=BULLISH"));
		assertEquals(0.25, book.evaluate(Set.of("dir=BULLISH")).sizeMultiplier(), 1e-9);
	}

	@Test
	void promptSectionCitesMeasuredStatsOnlyForMatchingRules() {
		withRules(rule(LearnedRule.Kind.PENALTY, 8, "Bullish social-led calls lose", "dir=BULLISH", "lead=SOCIAL"));

		String text = book.promptSection(Set.of("dir=BULLISH", "lead=SOCIAL"));

		assertTrue(text.contains("MEASURED") && text.contains("Bullish social-led calls lose"));
		assertTrue(text.contains("14 independent bets") && text.contains("win rate 33%") && text.contains("held-out"));
		assertEquals("", book.promptSection(Set.of("dir=BEARISH")));
	}

	@Test
	void aRepositoryFailureKeepsThePreviousRules() {
		withRules(rule(LearnedRule.Kind.PENALTY, 8, "p", "dir=BULLISH"));
		when(repo.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE)).thenThrow(new IllegalStateException("db down"));

		book.reload();

		assertEquals(-8, book.evaluate(Set.of("dir=BULLISH")).scoreDelta(), "an outage must not silently drop learned protections");
	}

	@Test
	void noneLessonsIsInert() {
		assertTrue(Lessons.none().evaluate(Set.of("dir=BULLISH")).isNone());
		assertEquals("", Lessons.none().promptSection(Set.of("dir=BULLISH")));
		assertNull(Lessons.none().evaluate(Set.of()).blockReason());
	}

	@Test
	void featureTokensRoundTripAndAreSortedForStableStorage() {
		String json = FeatureTokens.toJson(Set.of("sector=TECH", "dir=BULLISH"));

		assertEquals("[\"dir=BULLISH\",\"sector=TECH\"]", json);
		assertEquals(Set.of("dir=BULLISH", "sector=TECH"), FeatureTokens.fromJson(json));
		assertTrue(FeatureTokens.fromJson("not json").isEmpty());
		assertTrue(FeatureTokens.fromJson(null).isEmpty());
	}

	@Test
	void bucketsAreStable() {
		assertEquals("80+", FeatureTokens.convictionBucket(85));
		assertEquals("70-79", FeatureTokens.convictionBucket(70));
		assertEquals("62-69", FeatureTokens.convictionBucket(62));
		assertEquals("under10", FeatureTokens.priceBucket(3.0));
		assertEquals("200+", FeatureTokens.priceBucket(500.0));
		assertNull(FeatureTokens.priceBucket(null));
		assertEquals("high", FeatureTokens.volatilityBucket(6.0));
	}
}
