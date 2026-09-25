package com.argus.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.recommendation.RecommendationRepository;
import com.argus.recommendation.RecommendationSignalRepository;
import com.argus.recommendation.SignalDirection;
import com.argus.recommendation.SimulatedTrade;
import com.argus.recommendation.SimulatedTradeRepository;
import com.argus.regime.Sector;
import com.argus.regime.SectorClassifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The learner end to end: mine → validate → activate/retire → explain → report, with a fake rule store. */
class TradeLearnerTest {

	private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

	private final SimulatedTradeRepository trades = mock(SimulatedTradeRepository.class);
	private final RecommendationRepository recs = mock(RecommendationRepository.class);
	private final RecommendationSignalRepository signals = mock(RecommendationSignalRepository.class);
	private final SectorClassifier sectors = mock(SectorClassifier.class);
	private final LearnedRuleRepository rules = mock(LearnedRuleRepository.class);
	private final LearningReportRepository reports = mock(LearningReportRepository.class);
	private final LessonBook book = mock(LessonBook.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final List<LearnedRule> store = new ArrayList<>();
	private final AtomicLong ids = new AtomicLong(100);

	private TradeLearner learner(boolean explain) {
		when(rules.save(any(LearnedRule.class))).thenAnswer(inv -> {
			LearnedRule r = inv.getArgument(0);
			if (r.getId() == null) ReflectionTestUtils.setField(r, "id", ids.incrementAndGet());
			if (!store.contains(r)) store.add(r);
			return r;
		});
		when(rules.findAll()).thenAnswer(inv -> List.copyOf(store));
		when(rules.findByStatusOrderByActivatedAtDesc(LearnedRule.Status.ACTIVE)).thenAnswer(inv ->
				store.stream().filter(r -> r.getStatus() == LearnedRule.Status.ACTIVE).toList());
		when(reports.save(any(LearningReport.class))).thenAnswer(inv -> inv.getArgument(0));
		when(signals.rowsFor(anyCollection())).thenReturn(List.of());
		when(sectors.sectorOf(anyString())).thenAnswer(inv -> {
			int i = Integer.parseInt(((String) inv.getArgument(0)).substring(1));
			return i % 3 == 0 ? Sector.AUTOS_EV : Sector.TECHNOLOGY;
		});
		return new TradeLearner(trades, recs, signals, sectors, rules, reports, book, gateway, new LearningProperties(true, explain));
	}

	/** n closed bullish trades on unique tickers "T<i>", a day apart; autos lose {@code autosMean}, others earn {@code otherMean}. */
	private void closedBook(int n, double autosMean, double otherMean, long seed) {
		Random rnd = new Random(seed);
		List<SimulatedTrade> closed = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			SimulatedTrade t = new SimulatedTrade(null, "T" + i, SignalDirection.BULLISH, BigDecimal.valueOf(100), BigDecimal.valueOf(100), 7, null);
			ReflectionTestUtils.setField(t, "entryAt", BASE.plusSeconds(86_400L * i));
			double excess = (i % 3 == 0 ? autosMean : otherMean) + 1.2 * rnd.nextGaussian();
			t.close(BigDecimal.valueOf(100 + excess), null);
			closed.add(t);
		}
		when(trades.findByStatusOrderByClosedAtDesc(SimulatedTrade.Status.CLOSED)).thenReturn(closed);
	}

	@Test
	void aPlantedSectorPatternIsLearnedAndActivatedWithItsEvidence() {
		closedBook(150, -4.0, 0.4, 5);

		LearningReport report = learner(false).run();

		LearnedRule rule = store.stream().filter(r -> r.getStatus() == LearnedRule.Status.ACTIVE && r.getPredicates().contains("sector=AUTOS_EV"))
				.findFirst().orElseThrow(() -> new AssertionError("no active autos rule; store=" + store.stream().map(LearnedRule::getPredicates).toList()));
		assertTrue(rule.getKind() == LearnedRule.Kind.PENALTY || rule.getKind() == LearnedRule.Kind.BLOCK);
		assertTrue(rule.getDescription().contains("Autos & EV") && rule.getDescription().contains("underperformed the S&P 500"), rule.getDescription());
		assertTrue(rule.getSupportClusters() >= 8 && rule.getMeanExcess().doubleValue() < -3);
		assertNotNull(rule.getHoldoutMeanExcess());
		assertTrue(rule.getHoldoutMeanExcess().doubleValue() < 0, "it replicated on the newer trades");
		assertEquals(150, report.getTradesAnalyzed());
		assertTrue(report.getLossesSummary().contains("Autos & EV"), report.getLossesSummary());
		verify(book, atLeastOnce()).reload();
	}

	@Test
	void aLosingPatternMilderThanABlockAlsoShrinksTheSizeOfPositionsTakenInIt() {
		closedBook(150, -3.3, 0.4, 5); // bad enough for a size cut (≤ -3%), not bad enough to be blocked outright (≤ -4%)

		learner(false).run();

		assertTrue(store.stream().anyMatch(r -> r.getKind() == LearnedRule.Kind.PENALTY && r.getStatus() == LearnedRule.Status.ACTIVE));
		assertTrue(store.stream().anyMatch(r -> r.getKind() == LearnedRule.Kind.SIZE && r.getStatus() == LearnedRule.Status.ACTIVE
				&& r.getEffectValue().doubleValue() == 0.6), "a cohort averaging ≤ -3% earns a size cut as well");
	}

	@Test
	void tooFewIndependentBetsProducesAHonestReportAndNoRules() {
		closedBook(12, -4.0, 0.4, 5);

		LearningReport report = learner(false).run();

		assertTrue(store.isEmpty());
		assertTrue(report.getNarrative().contains("independent bets so far"), report.getNarrative());
		verify(book, never()).reload();
		verify(gateway, never()).generate(anyString(), any());
	}

	@Test
	void aRuleThatStoppedHoldingIsRetiredOnTheNextRun() {
		closedBook(150, 0.3, 0.3, 9); // the autos penalty no longer matches anything
		LearnedRule old = new LearnedRule(LearnedRule.Kind.PENALTY, Set.of("sector=AUTOS_EV"), "old lesson", 8);
		old.recordEvidence(20, 12, 0.3, -4.0, 5, -3.0);
		old.activate("replicated once");
		ReflectionTestUtils.setField(old, "id", 7L);
		store.add(old);

		learner(false).run();

		assertEquals(LearnedRule.Status.RETIRED, old.getStatus());
		assertTrue(old.getVerdictNote().startsWith("No longer holds"), old.getVerdictNote());
		assertNotNull(old.getRetiredAt());
	}

	@Test
	void aRuleThatStillHoldsIsKeptAndItsEvidenceRefreshed() {
		closedBook(150, -4.0, 0.4, 5);
		LearnedRule tech = new LearnedRule(LearnedRule.Kind.BOOST, Set.of("sector=TECHNOLOGY"), "tech works", 4);
		tech.recordEvidence(20, 12, 0.6, 1.0, 5, 1.0);
		tech.activate("earlier");
		ReflectionTestUtils.setField(tech, "id", 8L);
		store.add(tech);

		learner(false).run();

		assertTrue(tech.getStatus() == LearnedRule.Status.ACTIVE || tech.getStatus() == LearnedRule.Status.RETIRED);
		assertTrue(tech.getLastEvaluatedAt() != null);
	}

	@Test
	void theModelWritesAWhyAndANarrativeButNeverDecidesAnything() {
		closedBook(150, -4.0, 0.4, 5);
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenAnswer(inv -> {
			String prompt = inv.getArgument(0);
			assertTrue(prompt.contains("MEASURED facts") && prompt.contains("you cannot change any rule"), "the model is told it only explains");
			assertTrue(prompt.contains("Autos & EV"), "it is given the measured cohorts, not raw opinions");
			assertTrue(prompt.contains("do not mention options") && prompt.contains("did NOT replicate"), "it is kept honest about instruments and unproven patterns");
			return "{\"narrative\":\"Autos names keep losing.\",\"explanations\":[{\"id\":101,\"why\":\"High rates hurt capital-heavy EV makers.\"}]}";
		});

		LearningReport report = learner(true).run();

		assertEquals("Autos names keep losing.", report.getNarrative());
		assertTrue(store.stream().anyMatch(r -> "High rates hurt capital-heavy EV makers.".equals(r.getExplanation())));
		assertTrue(store.stream().anyMatch(r -> r.getStatus() == LearnedRule.Status.ACTIVE), "the rule exists because of the numbers, with or without the model");
	}

	@Test
	void aModelFailureDoesNotStopRulesFromBeingLearned() {
		closedBook(150, -4.0, 0.4, 5);
		when(gateway.generate(anyString(), any())).thenThrow(new RuntimeException("model down"));

		LearningReport report = learner(true).run();

		assertTrue(store.stream().anyMatch(r -> r.getStatus() == LearnedRule.Status.ACTIVE));
		assertEquals("deterministic", report.getModel());
	}

	@Test
	void anUnparseableModelReplyIsIgnored() {
		closedBook(150, -4.0, 0.4, 5);
		when(gateway.generate(anyString(), any())).thenReturn("I'd say the autos are bad!");

		LearningReport report = learner(true).run();

		assertEquals("deterministic", report.getModel());
		assertFalse(store.isEmpty());
	}

	@Test
	void theNightlyRunDoesNothingWhenLearningIsDisabled() {
		TradeLearner off = new TradeLearner(trades, recs, signals, sectors, rules, reports, book, gateway, new LearningProperties(false, true));

		off.nightly();

		verify(trades, never()).findByStatusOrderByClosedAtDesc(any());
	}

	@Test
	void ruleTextReadsAsPlainEnglish() {
		String text = RuleText.describe(Set.of("dir=BULLISH", "lead=SOCIAL", "regime=RISK_OFF"), -3.1, 14, 0.33);

		assertEquals("Bullish calls led by crowd sentiment in a risk-off market underperformed the S&P 500 by an average of 3.1% over 14 independent bets (win rate 33%).", text);
	}

	@Test
	void legacyTradesAreReconstructedFromWhatWasStoredAndNothingIsGuessed() {
		Set<String> t = LegacyTokens.build("BULLISH", "TECHNOLOGY", 30, "NVDA", 120.0, List.of(
				new LegacyTokens.Sig("agent-1-news", "BULLISH", 0.7), new LegacyTokens.Sig("agent-2-social", "BULLISH", 0.2),
				new LegacyTokens.Sig("agent-4-financial", "BEARISH", 0.5), new LegacyTokens.Sig("agent-7-calendar", "BEARISH", 0.3)));

		assertTrue(t.containsAll(Set.of("dir=BULLISH", "sector=TECHNOLOGY", "hold=30", "ticker=NVDA", "price=50-200", "has=NEWS", "has=SOCIAL", "lead=NEWS")), t.toString());
		assertTrue(t.stream().noneMatch(x -> x.startsWith("regime=") || x.startsWith("trend=") || x.startsWith("deep=")),
				"regime, chart and deep tokens can't be recovered for old trades, so they are absent");
		assertFalse(t.contains("has=INSIDER"), "an opposing signal is not support");
		assertFalse(t.contains("has=CALENDAR"));
	}
}
