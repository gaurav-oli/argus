package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.fundamentals.Fundamentals;
import com.argus.learning.LessonEffect;
import com.argus.learning.Lessons;
import com.argus.model.ModelGateway;
import com.argus.model.ModelGatewayException;
import com.argus.model.ModelTier;
import com.argus.notification.NotificationService;
import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.SignalDirection;
import com.argus.regime.MarketRegime;
import com.argus.regime.Sector;
import com.argus.technical.ChartStudy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The Agent 11 pipeline with a scripted model: the stage logic, slicing, fallbacks, guards and failure handling. */
class DeepAnalystServiceTest {

	private final EvidenceCollector collector = mock(EvidenceCollector.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final DeepAnalysisProperties props = new DeepAnalysisProperties(true, 3, Duration.ofDays(4), 40, Duration.ZERO, true, -8.0);
	private final DeepAnalystService service = new DeepAnalystService(collector, gateway, repo, props, notifications, Lessons.none());
	private final DeepAnalysis run = new DeepAnalysis("NVDA", "TEST");

	// ---- fixtures ----

	private static ChartStudy chart(double score) {
		return new ChartStudy(300, LocalDate.now(), 100, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND, 55.0, 0.5, 0.5, 2.0,
				1.0, 1.2, 50.0, -12.0, 95.0, 105.0, List.of(), 1.0, 2.0, score, "BULLISH", List.of("Trend: UPTREND — close 100."));
	}

	private static Fundamentals fundamentals(boolean applicable) {
		return new Fundamentals("NVDA", applicable, "Nvidia", "Semis", 5e6, Map.of(), List.of(), List.of(), null, null, applicable ? 0.6 : 0.0,
				"BULLISH", List.of("Growth: revenue +80.0% year-over-year."), Instant.now());
	}

	private static Evidence evidence(ChartStudy chart, Fundamentals f, boolean etf, Double price, Integer earningsDays) {
		return new Evidence("NVDA", Sector.SEMICONDUCTORS, etf, price, chart, f,
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 0.5, "news is positive")),
				"NEWS-BLOCK: Nvidia beats estimates\n", "INSIDER-BLOCK: none\n", "CROWD-BLOCK: mixed\n", "EARNINGS-BLOCK: none\n", earningsDays,
				"MACRO-BLOCK: tariffs\n", MarketRegime.unavailable());
	}

	private static String specialistJson(String stance, double strength) {
		return "{\"stance\":\"" + stance + "\",\"strength\":" + strength + ",\"summary\":\"The lens says " + stance
				+ ".\",\"keyPoints\":[\"point A\"],\"risks\":[\"risk A\"]}";
	}

	private static final String SKEPTIC = "{\"consensusDirection\":\"BULLISH\",\"counterpoints\":[\"crowded trade\"],\"severity\":0.2,\"summary\":\"Some crowding.\"}";

	private static String verdictJson(String verdict, Object hold, int conviction) {
		return "{\"verdict\":\"" + verdict + "\",\"holdDays\":" + hold + ",\"conviction\":" + conviction
				+ ",\"headline\":\"Buy the dip\",\"thesis\":\"A paragraph.\",\"bullCase\":\"bull\",\"bearCase\":\"bear\","
				+ "\"risks\":[\"macro shock\"],\"catalysts\":[\"earnings\"],\"invalidation\":\"below 90\"}";
	}

	/** Script the local model: each analyst is recognised by its role text. */
	private void script(String tech, String fund, String cat, String macro, String skeptic) {
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenAnswer(inv -> {
			String p = inv.getArgument(0);
			if (p.contains("chart technician")) return tech;
			if (p.contains("fundamental analyst")) return fund;
			if (p.contains("news and catalyst analyst")) return cat;
			if (p.contains("macro and sector strategist")) return macro;
			if (p.contains("You are the skeptic")) return skeptic;
			return "not json";
		});
	}

	private void arrange(Evidence ev) {
		when(repo.findById(anyLong())).thenReturn(Optional.of(run));
		when(repo.save(any(DeepAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));
		when(repo.findFirstByTickerAndStatusOrderByFinishedAtDesc(anyString(), any())).thenReturn(Optional.empty());
		when(collector.collect("NVDA")).thenReturn(ev);
	}

	private void analyze() {
		service.analyze(1L);
	}

	// ---- happy path ----

	@Test
	void aSupportedBuyCompletesWithVerdictHoldConvictionAndTheFullAuditTrail() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.7), specialistJson("NEUTRAL", 0.3), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 78));

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
		assertEquals(DeepVerdict.WORTH_BUYING, run.getVerdict());
		assertEquals(30, run.getHoldDays());
		assertNotNull(run.getConviction());
		assertTrue(run.getThesis().contains("paragraph"));
		assertEquals("below 90", run.getInvalidation());
		assertTrue(run.getRisks().contains("macro shock") && run.getCatalysts().contains("earnings"));
		assertTrue(run.getTechnicalSummary().startsWith("BULLISH") && run.getFundamentalSummary().contains("point A"));
		assertTrue(run.getEvidence().contains("NEWS-BLOCK") && run.getEvidence().contains("=== CHART"), "the exact evidence pack is stored for audit");
		assertNotNull(run.getExpiresAt());
		assertTrue(run.getStages().contains("timings"));
	}

	@Test
	void eachAnalystSeesOnlyItsOwnSliceOfTheEvidence() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WAIT", "null", 40));

		analyze();

		ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
		verify(gateway, times(5)).generate(prompts.capture(), eq(ModelTier.BIG));
		String technical = prompts.getAllValues().stream().filter(p -> p.contains("chart technician")).findFirst().orElseThrow();
		String catalyst = prompts.getAllValues().stream().filter(p -> p.contains("news and catalyst analyst")).findFirst().orElseThrow();
		assertTrue(technical.contains("Trend: UPTREND") && !technical.contains("NEWS-BLOCK"), "the chart analyst reads the chart, not the news");
		assertTrue(catalyst.contains("NEWS-BLOCK") && catalyst.contains("INSIDER-BLOCK") && !catalyst.contains("Trend: UPTREND"));
		assertTrue(technical.contains("Use ONLY the evidence below"), "every analyst is told not to invent facts");
	}

	// ---- skipped analysts ----

	@Test
	void anEtfSkipsTheFundamentalAnalystAndCapsConviction() {
		arrange(evidence(chart(0.7), fundamentals(false), true, 150.0, null));
		script(specialistJson("BULLISH", 1.0), "unused", specialistJson("BULLISH", 1.0), specialistJson("BULLISH", 1.0), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 99));

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
		verify(gateway, times(4)).generate(anyString(), eq(ModelTier.BIG)); // technical, catalyst, macro, skeptic — no fundamentals
		assertTrue(run.getFundamentalSummary().contains("no company fundamentals"));
		assertTrue(run.getConviction() <= 70, "an ETF has no fundamentals: conviction capped at 70");
		assertTrue(run.getGuardNotes().contains("ETF"));
	}

	@Test
	void withoutEnoughPriceHistoryTheChartAnalystIsSkippedAndConvictionCapped() {
		arrange(evidence(null, fundamentals(true), false, 150.0, null));
		script("unused", specialistJson("BULLISH", 1.0), specialistJson("BULLISH", 1.0), specialistJson("BULLISH", 1.0), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 95));

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
		assertTrue(run.getTechnicalSummary().contains("not enough price history"));
		assertTrue(run.getConviction() <= 60);
	}

	// ---- the guard has the last word ----

	@Test
	void anLlmBuyAgainstNeutralAnalystsIsDowngradedToWaitWithAnExplanation() {
		arrange(evidence(chart(0.1), fundamentals(true), false, 150.0, null));
		script(specialistJson("NEUTRAL", 0.2), specialistJson("NEUTRAL", 0.2), specialistJson("NEUTRAL", 0.2), specialistJson("NEUTRAL", 0.2), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 90));

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus());
		assertEquals(DeepVerdict.WAIT, run.getVerdict(), "the model may not buy against the evidence");
		assertNull(run.getHoldDays());
		assertTrue(run.getGuardNotes().contains("Downgraded to WAIT"));
	}

	@Test
	void earningsDaysAwayForceWaitEvenWithUnanimousBulls() {
		arrange(evidence(chart(0.7), fundamentals(true), false, 150.0, 2));
		script(specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 85));

		analyze();

		assertEquals(DeepVerdict.WAIT, run.getVerdict());
		assertTrue(run.getGuardNotes().contains("earnings"));
	}

	// ---- model failures ----

	@Test
	void aBadVerdictJsonFailsTheRunRatherThanInventingAVerdict() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn("I think it's a buy!");

		analyze();

		assertEquals(DeepAnalysis.Status.FAILED, run.getStatus());
		assertTrue(run.getError().contains("no usable verdict"));
		assertNull(run.getVerdict());
	}

	@Test
	void anInvalidVerdictWordFailsTheRun() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("STRONG_MAYBE", 30, 80));

		analyze();

		assertEquals(DeepAnalysis.Status.FAILED, run.getStatus());
	}

	@Test
	void ifHaikuIsUnavailableTheLocalModelMakesTheVerdict() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		when(gateway.escalate(anyString())).thenThrow(new ModelGatewayException("no Anthropic key"));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenAnswer(inv -> {
			String p = inv.getArgument(0);
			if (p.contains("portfolio manager")) return verdictJson("WORTH_BUYING", 30, 75);
			if (p.contains("You are the skeptic")) return SKEPTIC;
			return specialistJson("BULLISH", 0.8);
		});

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
		assertEquals(DeepVerdict.WORTH_BUYING, run.getVerdict());
	}

	@Test
	void haikuIsNeverCalledWhenDisabled() {
		DeepAnalystService noHaiku = new DeepAnalystService(collector, gateway, repo,
				new DeepAnalysisProperties(true, 3, Duration.ofDays(4), 40, Duration.ZERO, false, -8.0), notifications, Lessons.none());
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenAnswer(inv -> {
			String p = inv.getArgument(0);
			if (p.contains("portfolio manager")) return verdictJson("WAIT", "null", 50);
			if (p.contains("You are the skeptic")) return SKEPTIC;
			return specialistJson("BULLISH", 0.8);
		});

		noHaiku.analyze(1L);

		verify(gateway, never()).escalate(anyString());
		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
	}

	@Test
	void anUnparseableAnalystIsRetriedOnceAndOtherwiseSkippedWhileTheRunContinues() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script("garbage", specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 75));

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
		assertTrue(run.getTechnicalSummary().contains("could not be parsed"));
		verify(gateway, times(6)).generate(anyString(), eq(ModelTier.BIG)); // the broken analyst was retried once
	}

	@Test
	void tooFewUsableAnalystsFailsTheRun() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script("garbage", "garbage", "garbage", specialistJson("BULLISH", 0.8), SKEPTIC);

		analyze();

		assertEquals(DeepAnalysis.Status.FAILED, run.getStatus());
		assertTrue(run.getError().contains("too little to reach a verdict"));
	}

	@Test
	void aFencedJsonReplyIsStillParsed() {
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script("```json\n" + specialistJson("BULLISH", 0.8) + "\n```", specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8),
				specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn("```json\n" + verdictJson("WORTH_BUYING", 30, 75) + "\n```");

		analyze();

		assertEquals(DeepAnalysis.Status.DONE, run.getStatus(), run.getError());
	}

	// ---- announcements ----

	@Test
	void aNewHighConvictionBuyIsAnnouncedButARepeatIsNot() {
		arrange(evidence(chart(0.8), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.95), specialistJson("BULLISH", 0.95), specialistJson("BULLISH", 0.95), specialistJson("BULLISH", 0.95), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 90));

		analyze();
		assertEquals(DeepVerdict.WORTH_BUYING, run.getVerdict());
		verify(notifications, times(1)).notify(any());

		DeepAnalysis prior = new DeepAnalysis("NVDA", "OLD");
		prior.complete(DeepVerdict.WORTH_BUYING, 30, 80, "h", "t", "b", "b", "", "", "", "", Duration.ofDays(1));
		when(repo.findFirstByTickerAndStatusOrderByFinishedAtDesc(anyString(), any())).thenReturn(Optional.of(prior));
		DeepAnalysis second = new DeepAnalysis("NVDA", "TEST2");
		when(repo.findById(anyLong())).thenReturn(Optional.of(second));

		service.analyze(2L);

		verify(notifications, times(1)).notify(any()); // still just the one — no repeat alert for an unchanged verdict
	}

	@Test
	void unknownRunIdIsANoOp() {
		when(repo.findById(anyLong())).thenReturn(Optional.empty());

		service.analyze(99L);

		verify(collector, never()).collect(anyString());
	}

	@Test
	void verdictAndStanceParsingIsForgiving() {
		assertEquals(DeepVerdict.WORTH_BUYING, DeepAnalystService.parseVerdict(" worth buying "));
		assertEquals(DeepVerdict.NOT_WORTH_BUYING, DeepAnalystService.parseVerdict("not-worth-buying"));
		assertNull(DeepAnalystService.parseVerdict("maybe"));
		assertEquals(DeepDecision.NEUTRAL, DeepDecision.of(DeepAnalystService.parseStance("who knows")));
		assertFalse(new ArrayList<>(List.of()).iterator().hasNext());
	}

	// ---- learned lessons reach Agent 11 ----

	private Lessons lessonsWith(LessonEffect fx, String text, java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> seen) {
		return new Lessons() {
			@Override
			public LessonEffect evaluate(java.util.Set<String> tokens) {
				seen.set(tokens);
				return fx;
			}

			@Override
			public String promptSection(java.util.Set<String> tokens) {
				return text;
			}
		};
	}

	@Test
	void measuredLessonsAreShownToTheSkepticAndThePortfolioManagerAndEvaluatedForABuy() {
		var seen = new java.util.concurrent.atomic.AtomicReference<java.util.Set<String>>();
		DeepAnalystService learning = new DeepAnalystService(collector, gateway, repo, props, notifications,
				lessonsWith(LessonEffect.none(), "LESSONS MEASURED FROM ARGUS'S OWN PAST PAPER TRADES: bullish semis calls lost 3%.", seen));
		arrange(evidence(chart(0.6), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), specialistJson("BULLISH", 0.8), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 75));

		learning.analyze(1L);

		ArgumentCaptor<String> local = ArgumentCaptor.forClass(String.class);
		verify(gateway, atLeastOnce()).generate(local.capture(), eq(ModelTier.BIG));
		String skeptic = local.getAllValues().stream().filter(p -> p.contains("You are the skeptic")).findFirst().orElseThrow();
		assertTrue(skeptic.contains("LESSONS MEASURED"), "the skeptic sees what past trades taught");
		ArgumentCaptor<String> pm = ArgumentCaptor.forClass(String.class);
		verify(gateway).escalate(pm.capture());
		assertTrue(pm.getValue().contains("LESSONS MEASURED") && pm.getValue().contains("explain if you override one"));
		assertTrue(seen.get().contains("dir=BULLISH") && seen.get().contains("sector=SEMICONDUCTORS"), "evaluated as a would-be buy: " + seen.get());
		assertTrue(local.getAllValues().stream().filter(p -> p.contains("chart technician")).noneMatch(p -> p.contains("LESSONS MEASURED")),
				"the specialists reason over evidence; the lessons go to the deciders");
	}

	@Test
	void aLessonBlockDowngradesTheVerdictThroughTheGuard() {
		var fx = new LessonEffect(0, "buying semis into a selloff has lost money", null, 1.0,
				List.of(new LessonEffect.Applied(1L, "BLOCK", "buying semis into a selloff has lost money", "12 bets", 0)));
		DeepAnalystService blocked = new DeepAnalystService(collector, gateway, repo, props, notifications,
				lessonsWith(fx, "", new java.util.concurrent.atomic.AtomicReference<>()));
		arrange(evidence(chart(0.7), fundamentals(true), false, 150.0, null));
		script(specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), specialistJson("BULLISH", 0.9), SKEPTIC);
		when(gateway.escalate(anyString())).thenReturn(verdictJson("WORTH_BUYING", 30, 85));

		blocked.analyze(1L);

		assertEquals(DeepVerdict.WAIT, run.getVerdict());
		assertTrue(run.getGuardNotes().contains("a lesson learned from past trades blocks buying"));
	}

	@Test
	void theEvidencePackDescribesItsSituationInTheSharedTokenVocabulary() {
		Evidence ev = evidence(chart(0.6), fundamentals(true), false, 150.0, 3);

		java.util.Set<String> t = ev.featureTokens();

		assertTrue(t.containsAll(java.util.Set.of("dir=BULLISH", "sector=SEMICONDUCTORS", "ticker=NVDA", "trend=UPTREND", "chart=BULLISH",
				"price=50-200", "earnings=soon", "has=NEWS", "lead=NEWS")), t.toString());
	}

	/** Tiny alias so the last assertion reads clearly. */
	private enum DeepDecision {
		NEUTRAL, OTHER;

		static DeepDecision of(DeepVerdictGuard.Stance s) {
			return s == DeepVerdictGuard.Stance.NEUTRAL ? NEUTRAL : OTHER;
		}
	}
}
