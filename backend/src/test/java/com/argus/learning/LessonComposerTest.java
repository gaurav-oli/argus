package com.argus.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LessonComposerTest {

	private static final Instant ENTRY = Instant.parse("2026-09-01T14:00:00Z");
	private static final Instant CLOSE = Instant.parse("2026-10-01T14:00:00Z");

	private static LessonComposer.CallFacts call() {
		return new LessonComposer.CallFacts("Buy", 72, new BigDecimal("0.71"), "Insider buying and four straight beats. More text here.",
				List.of(new LessonComposer.Signal("agent-11-deep", "BULLISH", new BigDecimal("1.4")),
						new LessonComposer.Signal("agent-4-financial", "BULLISH", new BigDecimal("0.9")),
						new LessonComposer.Signal("agent-2-social", "BEARISH", new BigDecimal("-0.5")),
						new LessonComposer.Signal("agent-1-news", "BULLISH", new BigDecimal("0.3")),
						new LessonComposer.Signal("agent-8-macro", "BULLISH", new BigDecimal("0.1"))));
	}

	private static LessonComposer.TradeFacts trade(boolean won, String exit, String review) {
		return new LessonComposer.TradeFacts("DOL.TO", "BULLISH", won, new BigDecimal(won ? "4.25" : "-2.1"),
				new BigDecimal(won ? "1.8" : "-3.04"), exit, ENTRY, CLOSE, review);
	}

	@Test
	void whyEnteredUsesTheCallTheOddsAndTheTopSignalsOnItsSide() {
		LessonComposer.Composed c = LessonComposer.compose(trade(true, "HORIZON", null), call());
		assertThat(c.whyEntered()).isEqualTo("Went long DOL.TO on a buy call (conviction 72, 71% odds on its side)."
				+ " Driven by Deep Analyst, SEC insider, News. Thesis: Insider buying and four straight beats.");
		assertThat(c.reliedOn()).containsExactly("agent-11-deep", "agent-4-financial", "agent-1-news");
	}

	@Test
	void outcomeStatesResultBenchmarkExitAndDays() {
		assertThat(LessonComposer.compose(trade(true, "HORIZON", null), call()).outcome())
				.isEqualTo("Won +4.3% (+1.8 pts vs SPY) — held to its horizon after 30 days.");
		assertThat(LessonComposer.compose(trade(false, "TRAILING_STOP", "x"), call()).outcome())
				.isEqualTo("Lost -2.1% (-3.0 pts vs SPY) — the trailing stop was hit after 30 days.");
	}

	@Test
	void aLossUsesThePostMortemAndSaysSoWhenThereIsNone() {
		assertThat(LessonComposer.compose(trade(false, "STOP", "Rates rose; the thesis ignored them."), call()).lesson())
				.isEqualTo("Rates rose; the thesis ignored them.");
		assertThat(LessonComposer.compose(trade(false, "STOP", "  "), call()).lesson())
				.isEqualTo("No post-mortem was written for this loss.");
	}

	@Test
	void aWinNamesTheSignalsThatWereRight() {
		assertThat(LessonComposer.compose(trade(true, "HORIZON", null), call()).lesson())
				.isEqualTo("The thesis held: Deep Analyst, SEC insider, News were right on direction.");
	}

	@Test
	void aShortCountsBearishSignalsAndInvertsTheOdds() {
		LessonComposer.TradeFacts shortTrade = new LessonComposer.TradeFacts("ENB.TO", "BEARISH", true, new BigDecimal("2"),
				null, "THESIS_FLIP", ENTRY, CLOSE, null);
		LessonComposer.Composed c = LessonComposer.compose(shortTrade, call());
		assertThat(c.whyEntered()).contains("Went short ENB.TO").contains("29% odds on its side").contains("Driven by Social.");
		assertThat(c.outcome()).isEqualTo("Won +2.0% — closed early when Agent 11 turned against it after 30 days.");
	}

	@Test
	void aMissingRecommendationIsSaidPlainly() {
		LessonComposer.Composed c = LessonComposer.compose(trade(true, "HORIZON", null), null);
		assertThat(c.whyEntered()).isEqualTo("Went long DOL.TO. (The recommendation behind it is no longer stored.)");
		assertThat(c.lesson()).isEqualTo("The call worked, but no individual signal is recorded for it.");
		assertThat(c.reliedOn()).isEmpty();
	}

	// ---- what changed ----

	private static final Instant NOW = CLOSE.plus(Duration.ofDays(2));
	private static final LessonComposer.Review ADOPTED = new LessonComposer.Review(CLOSE.plus(Duration.ofHours(13)), true, "Brier improved",
			List.of(new LessonComposer.Proposal("agent-11-deep", 1.1, "beat SPY"), new LessonComposer.Proposal("agent-2-social", 0.9, "noisy")));
	private static final LessonComposer.Review KEPT = new LessonComposer.Review(CLOSE.plus(Duration.ofHours(13)), false,
			"proposal hurt accuracy", List.of());

	@Test
	void anAdoptedReviewIsAWeightChangeAndFlagsAgentsTheTradeReliedOn() {
		LessonComposer.Change ch = LessonComposer.resolveChange(CLOSE, List.of("agent-11-deep"), List.of(ADOPTED), List.of(), true, NOW);
		assertThat(ch.kind()).isEqualTo("WEIGHTS_ADJUSTED");
		assertThat(ch.summary()).isEqualTo("Logic review on Oct 1 adjusted weights: Deep Analyst ×1.10 (this trade relied on it) · Social ×0.90.");
	}

	@Test
	void ruleChangesAreReportedAndCombineWithWeights() {
		List<LessonComposer.RuleChange> rules = List.of(
				new LessonComposer.RuleChange(CLOSE.plusSeconds(60), true, "Skip adds within 48h of earnings"),
				new LessonComposer.RuleChange(CLOSE.plusSeconds(90), false, "Old rule"));
		LessonComposer.Change onlyRules = LessonComposer.resolveChange(CLOSE, List.of(), List.of(KEPT), rules, true, NOW);
		assertThat(onlyRules.kind()).isEqualTo("RULE_ACTIVATED");
		assertThat(onlyRules.summary()).isEqualTo("Agent 13 activated “Skip adds within 48h of earnings”. Agent 13 retired “Old rule”.");
		assertThat(LessonComposer.resolveChange(CLOSE, List.of(), List.of(ADOPTED), rules, true, NOW).kind()).isEqualTo("WEIGHTS_ADJUSTED");
		assertThat(LessonComposer.resolveChange(CLOSE, List.of(), List.of(), rules.subList(1, 2), false, NOW).kind()).isEqualTo("RULE_RETIRED");
	}

	@Test
	void noChangeIsExplicitOnlyOnceBothNightlyJobsHaveRun() {
		LessonComposer.Change ch = LessonComposer.resolveChange(CLOSE, List.of(), List.of(KEPT), List.of(), true, NOW);
		assertThat(ch.kind()).isEqualTo("NO_CHANGE");
		assertThat(ch.summary()).isEqualTo("No change: the logic review on Oct 1 kept the weights (proposal hurt accuracy), and Agent 13 changed no rules.");
		// Only one of the two has run: still honestly pending.
		assertThat(LessonComposer.resolveChange(CLOSE, List.of(), List.of(KEPT), List.of(), false, NOW).kind()).isEqualTo("PENDING");
		assertThat(LessonComposer.resolveChange(CLOSE, List.of(), List.of(), List.of(), true, NOW).kind()).isEqualTo("PENDING");
	}

	@Test
	void stopsWaitingAfterAWeekAndSaysWhichJobNeverRan() {
		Instant late = CLOSE.plus(Duration.ofDays(8));
		LessonComposer.Change ch = LessonComposer.resolveChange(CLOSE, List.of(), List.of(), List.of(), false, late);
		assertThat(ch.kind()).isEqualTo("NO_CHANGE");
		assertThat(ch.summary()).contains("neither the logic review nor Agent 13 has run");
		assertThat(LessonComposer.resolveChange(CLOSE, List.of(), List.of(KEPT), List.of(), false, late).summary()).contains("Agent 13 hasn't run");
	}

	@Test
	void agentNamesAreFriendlyAndUnknownOnesTidied() {
		assertThat(LessonComposer.agentName("agent-10-technical")).isEqualTo("Chart Reader");
		assertThat(LessonComposer.agentName("agent-16-pattern-matcher")).isEqualTo("Pattern matcher");
		assertThat(LessonComposer.agentName(null)).isEqualTo("an agent");
	}
}
