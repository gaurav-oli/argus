package com.argus.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.CalendarEventRepository;
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisRunner;
import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.deepanalysis.DeepVerdict;
import com.argus.fundamentals.Fundamentals;
import com.argus.fundamentals.FundamentalsService;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import com.argus.common.BadRequestException;
import com.argus.common.LivePushService;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.internet.WebMentionRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.research.ResearchAgentService.Step;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Agent 9's orchestration: ticker validation, defensive plan/replan JSON parsing (same shape as
 * {@code LogicReviewService}/{@code MacroKeywordLearningService}), and the full plan→gather→replan→
 * synthesize pipeline run synchronously via the package-visible {@link ResearchAgentService#runPipeline}
 * rather than racing the real background executor {@code startJob} uses.
 */
class ResearchAgentServiceTest {

	private final ResearchJobRepository jobs = mock(ResearchJobRepository.class);
	private final NewsArticleRepository news = mock(NewsArticleRepository.class);
	private final SocialPostRepository social = mock(SocialPostRepository.class);
	private final SecFilingRepository sec = mock(SecFilingRepository.class);
	private final WebMentionRepository web = mock(WebMentionRepository.class);
	private final CalendarEventRepository calendar = mock(CalendarEventRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final LivePushService livePush = mock(LivePushService.class);
	private final ResearchJobProperties props = new ResearchJobProperties(2, 30);
	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final FundamentalsService fundamentals = mock(FundamentalsService.class);
	private final DeepAnalysisService deepAnalyses = mock(DeepAnalysisService.class);
	private final DeepAnalysisRunner deepRunner = mock(DeepAnalysisRunner.class);
	private final com.argus.filings.FilingDigestService filings = mock(com.argus.filings.FilingDigestService.class);
	private final com.argus.deepanalysis.DeepScorecardService scorecard = mock(com.argus.deepanalysis.DeepScorecardService.class);

	private final ResearchAgentService service = new ResearchAgentService(
			jobs, news, social, sec, web, calendar, gateway, livePush, props, charts, fundamentals, deepAnalyses, deepRunner, filings, scorecard);

	{
		// Default every raw-data source to empty unless a test overrides it — keeps each test focused
		// on what it's actually exercising.
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of());
		when(social.findByTickerAndPostedAtAfter(anyString(), any())).thenReturn(List.of());
		when(sec.findByTickerAndTransactionTypeInAndFiledAtAfter(anyString(), any(), any())).thenReturn(List.of());
		when(web.findByTickerAndPostedAtAfter(anyString(), any())).thenReturn(List.of());
		when(calendar.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(anyString(), any(), any(), any()))
				.thenReturn(List.of());
		when(charts.studyFor(anyString())).thenReturn(Optional.empty());
		when(fundamentals.getOrRefresh(anyString(), any())).thenReturn(Optional.empty());
		when(deepAnalyses.latestDone(anyString())).thenReturn(Optional.empty());
		when(filings.view(anyString())).thenReturn(Optional.empty());
		when(filings.refresh(anyString())).thenReturn(List.of());
	}

	// ---- ticker validation ----

	@Test
	void startJobRejectsBlankOrNullTicker() {
		assertThrows(BadRequestException.class, () -> service.startJob(""));
		assertThrows(BadRequestException.class, () -> service.startJob("   "));
		assertThrows(BadRequestException.class, () -> service.startJob(null));
		verify(jobs, never()).save(any());
	}

	@Test
	void startJobRejectsMalformedTicker() {
		assertThrows(BadRequestException.class, () -> service.startJob("not a ticker!"));
		assertThrows(BadRequestException.class, () -> service.startJob("TOOLONGTICKER"));
		verify(jobs, never()).save(any());
	}

	@Test
	void startJobNormalizesAndSavesAValidTicker() {
		when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));

		ResearchJob job = service.startJob(" spcx ");

		assertEquals("SPCX", job.getTicker());
		assertEquals(ResearchJob.Status.PLANNING, job.getStatus());
	}

	@Test
	void startJobAcceptsADottedExchangeSuffix() {
		when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));

		ResearchJob job = service.startJob("brk.b");

		assertEquals("BRK.B", job.getTicker());
	}

	// ---- parseSteps: defensive JSON extraction ----

	@Test
	void parseStepsParsesAWellFormedArray() {
		List<Step> steps = ResearchAgentService.parseSteps(
				"[{\"label\":\"Check news\",\"dataSource\":\"news\",\"why\":\"recent coverage\"}]");

		assertEquals(1, steps.size());
		assertEquals("Check news", steps.get(0).label());
		assertEquals("NEWS", steps.get(0).dataSource(), "dataSource is normalized to uppercase");
		assertEquals("PENDING", steps.get(0).status());
	}

	@Test
	void parseStepsToleratesSurroundingProseAndCodeFences() {
		List<Step> steps = ResearchAgentService.parseSteps("""
				Sure, here's my plan:
				```json
				[{"label":"Insider activity","dataSource":"INSIDER","why":"conviction signal"}]
				```
				""");

		assertEquals(1, steps.size());
		assertEquals("INSIDER", steps.get(0).dataSource());
	}

	@Test
	void parseStepsSkipsUnknownDataSourcesRatherThanGuessing() {
		List<Step> steps = ResearchAgentService.parseSteps(
				"[{\"label\":\"Bogus\",\"dataSource\":\"FUNDAMENTALS\",\"why\":\"x\"},"
						+ "{\"label\":\"Real\",\"dataSource\":\"WEB\",\"why\":\"x\"}]");

		assertEquals(1, steps.size());
		assertEquals("Real", steps.get(0).label());
	}

	@Test
	void parseStepsReturnsEmptyOnMalformedOrNullInput() {
		assertTrue(ResearchAgentService.parseSteps("not json at all").isEmpty());
		assertTrue(ResearchAgentService.parseSteps(null).isEmpty());
		assertTrue(ResearchAgentService.parseSteps("[]").isEmpty());
	}

	// ---- readPlan ----

	@Test
	void readPlanParsesAPersistedPlan() {
		List<Step> steps = ResearchAgentService.readPlan(
				"[{\"id\":\"s1\",\"label\":\"L\",\"dataSource\":\"NEWS\",\"why\":\"W\",\"status\":\"DONE\"}]");

		assertEquals(1, steps.size());
		assertEquals("DONE", steps.get(0).status());
	}

	@Test
	void readPlanReturnsEmptyForBlankOrInvalidJson() {
		assertTrue(ResearchAgentService.readPlan(null).isEmpty());
		assertTrue(ResearchAgentService.readPlan("").isEmpty());
		assertTrue(ResearchAgentService.readPlan("not json").isEmpty());
	}

	// ---- full pipeline ----

	@Test
	void happyPathPipelineProducesADoneJobWithTheSynthesizedReport() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"News check\",\"dataSource\":\"NEWS\",\"why\":\"x\"},"
						+ "{\"label\":\"Insider check\",\"dataSource\":\"INSIDER\",\"why\":\"x\"}]");
		when(gateway.generate(contains("If these findings suggest"), eq(ModelTier.BIG))).thenReturn("NO_CHANGE");
		when(gateway.escalate(anyString())).thenReturn("# SPCX Report\n\nBullish long-term lean.");

		service.runPipeline(1L);

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertEquals("# SPCX Report\n\nBullish long-term lean.", job.getReport());
		assertNull(job.getError());
		List<Step> finalPlan = ResearchAgentService.readPlan(job.getPlan());
		assertTrue(finalPlan.stream().allMatch(s -> "DONE".equals(s.status())));
		verify(livePush, org.mockito.Mockito.atLeastOnce()).publish(anyString(), any());
	}

	private ResearchJob runSingleStep(String dataSource) {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"Step\",\"dataSource\":\"" + dataSource + "\",\"why\":\"x\"}]");
		when(gateway.escalate(anyString())).thenReturn("report");
		service.runPipeline(1L);
		return job;
	}

	private static Fundamentals fundamentalsOf() {
		return new Fundamentals("SPCX", true, "SpaceCo", "Aerospace", 5000.0, java.util.Map.of(), List.of(), List.of(), null, null, 0.42,
				"BULLISH", List.of("Growth: revenue +31.0% year-over-year.", "Earnings: beat EPS estimates in 4 of the last 4 quarters."),
				java.time.Instant.now());
	}

	@Test
	void financialsNowReturnsAgent12sFullFundamentalAnalysis() {
		when(fundamentals.getOrRefresh(eq("SPCX"), any())).thenReturn(Optional.of(fundamentalsOf()));

		ResearchJob job = runSingleStep("FINANCIALS");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("revenue +31.0% year-over-year"), "the growth analysis must appear");
		assertTrue(job.getFindings().contains("beat EPS estimates in 4 of the last 4"), "and the earnings track record");
	}

	@Test
	void financialsDegradesGracefullyWhenNoFundamentalsAreAvailable() {
		ResearchJob job = runSingleStep("FINANCIALS");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("Financial data unavailable"));
	}

	private static ChartStudy chart() {
		return new ChartStudy(300, java.time.LocalDate.now(), 100, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND, 55.0, 0.5, 0.5,
				2.0, 1.0, 1.2, 50.0, -3.0, 95.0, 105.0, List.of(), 1.0, 2.0, 0.5, "BULLISH",
				List.of("Trend: UPTREND — close 100.00 vs SMA20 100.00.", "Candlestick (2026-09-23): Hammer — bullish, after a 3.0% decline."));
	}

	@Test
	void technicalReturnsAgent10sChartStudy() {
		when(charts.studyFor("SPCX")).thenReturn(Optional.of(chart()));

		ResearchJob job = runSingleStep("TECHNICAL");

		assertTrue(job.getFindings().contains("Trend: UPTREND") && job.getFindings().contains("Hammer"), job.getFindings());
	}

	@Test
	void technicalSaysSoWhenThereIsNotEnoughPriceHistory() {
		ResearchJob job = runSingleStep("TECHNICAL");

		assertTrue(job.getFindings().contains("not enough daily price history"));
	}

	private static DeepAnalysis deepDone(java.time.Duration ttl) {
		DeepAnalysis d = new DeepAnalysis("SPCX", "TEST");
		d.complete(DeepVerdict.WORTH_BUYING, 30, 74, "A durable grower on a dip", "It is a good business.", "Strong growth", "Rich valuation",
				"macro shock\nlosing a contract", "", "a close below 90", "Conviction capped at 65.", ttl);
		return d;
	}

	@Test
	void deepReturnsAgent11sLatestVerdictWithItsReasoning() {
		when(deepAnalyses.latestDone("SPCX")).thenReturn(Optional.of(deepDone(java.time.Duration.ofDays(3))));

		ResearchJob job = runSingleStep("DEEP");

		assertTrue(job.getFindings().contains("Worth buying") && job.getFindings().contains("hold about 30 days"), job.getFindings());
		assertTrue(job.getFindings().contains("A durable grower on a dip") && job.getFindings().contains("Rich valuation"));
		assertTrue(job.getFindings().contains("a close below 90") && job.getFindings().contains("Conviction capped at 65."));
		verify(deepRunner, never()).enqueue(anyString(), anyString());
	}

	@Test
	void deepFlagsAStaleVerdict() {
		when(deepAnalyses.latestDone("SPCX")).thenReturn(Optional.of(deepDone(java.time.Duration.ofDays(-1))));

		ResearchJob job = runSingleStep("DEEP");

		assertTrue(job.getFindings().contains("now stale"));
	}

	@Test
	void deepQueuesAnAnalysisAndSaysItIsNotInTheReportWhenNoneExists() {
		ResearchJob job = runSingleStep("DEEP");

		verify(deepRunner).enqueue("SPCX", "RESEARCH");
		assertTrue(job.getFindings().contains("has not analysed this ticker yet") && job.getFindings().contains("NOT part of this report"));
		assertEquals(ResearchJob.Status.DONE, job.getStatus(), "Agent 9 never waits on Agent 11");
	}

	@Test
	void deepStillCompletesWhenQueuingFails() {
		org.mockito.Mockito.doThrow(new RuntimeException("queue down")).when(deepRunner).enqueue(anyString(), anyString());

		ResearchJob job = runSingleStep("DEEP");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
	}

	@Test
	void theSynthesisPromptAsksToReconcileWithAgent11AndForbidsInventing() {
		ResearchJob job = runSingleStep("NEWS");

		org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(gateway).escalate(prompt.capture());
		assertTrue(prompt.getValue().contains("Agent 11's verdict") && prompt.getValue().contains("Do not invent any figure"));
		assertEquals(ResearchJob.Status.DONE, job.getStatus());
	}

	@Test
	void planFallsBackToTheDefaultWhenTheModelCallFails() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenThrow(new RuntimeException("model down"));
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		List<Step> finalPlan = ResearchAgentService.readPlan(job.getPlan());
		assertEquals(10, finalPlan.size(), "the default (all 10 sources) plan is used when planning fails");
		assertEquals(ResearchJob.Status.DONE, job.getStatus(),
				"a failed plan call must not fail the whole job — the default plan carries it through");
	}

	@Test
	void replanCheckCanReviseTheRemainingSteps() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"News\",\"dataSource\":\"NEWS\",\"why\":\"x\"},"
						+ "{\"label\":\"Social\",\"dataSource\":\"SOCIAL\",\"why\":\"x\"}]");
		when(gateway.generate(contains("If these findings suggest"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"Insider dig-in\",\"dataSource\":\"INSIDER\",\"why\":\"unusual buying\"}]");
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		List<Step> finalPlan = ResearchAgentService.readPlan(job.getPlan());
		assertTrue(finalPlan.stream().anyMatch(s -> s.label().equals("Insider dig-in")),
				"the revision must replace the original remaining step");
		assertFalse(finalPlan.stream().anyMatch(s -> s.label().equals("Social")),
				"the step the revision replaced must be gone, not merely appended");
		assertEquals(ResearchJob.Status.DONE, job.getStatus());
	}

	@Test
	void replanIsNeverCheckedAfterTheLastStep() {
		// Only one step, so there's nothing left to revise — the replan prompt must never fire.
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"News\",\"dataSource\":\"NEWS\",\"why\":\"x\"}]");
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		verify(gateway, never()).generate(contains("If these findings suggest"), eq(ModelTier.BIG));
		assertEquals(ResearchJob.Status.DONE, job.getStatus());
	}

	@Test
	void unexpectedFailureMarksTheJobFailedEvenWhenPersistingThatFailureAlsoFails() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"News\",\"dataSource\":\"NEWS\",\"why\":\"x\"}]");
		when(jobs.save(any())).thenThrow(new RuntimeException("db down"));

		service.runPipeline(1L); // must not throw out of the pipeline

		assertEquals(ResearchJob.Status.FAILED, job.getStatus(),
				"the in-memory entity must reflect FAILED even if persisting that fact also fails");
		assertEquals("db down", job.getError());
	}

	@Test
	void synthesisFailureStillProducesADoneJobWithAFallbackReport() {
		// Synthesis failing shouldn't fail the whole job — findings were gathered, just no report could
		// be written; the fallback report says so honestly instead of leaving the job stuck.
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"News\",\"dataSource\":\"NEWS\",\"why\":\"x\"}]");
		when(gateway.escalate(anyString())).thenThrow(new RuntimeException("haiku unavailable"));

		service.runPipeline(1L);

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getReport().contains("haiku unavailable"));
	}

	// ---- Agent 14 filings + Agent 11 thesis status / track record ----

	@Test
	void filingsReportsGuidanceToneAndTheDigestSummaries() {
		when(filings.view("SPCX")).thenReturn(Optional.of(new com.argus.filings.FilingView("SPCX", 0.55, "RAISED", "CONFIDENT",
				java.time.LocalDate.now().minusDays(4), "Raised guidance", 4, List.of(new com.argus.filings.FilingView.Item("8-K", "EARNINGS_RELEASE",
						java.time.LocalDate.now().minusDays(4), "Revenue beat; full-year outlook raised.", "RAISED", "FY revenue $9-9.2B", "CONFIDENT", 0.55, 6, 0,
						"0001")))));

		ResearchJob job = runSingleStep("FILINGS");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("guidance RAISED") && job.getFindings().contains("full-year outlook raised"), job.getFindings());
		verify(filings).refresh("SPCX");
	}

	@Test
	void filingsSaysSoWhenThereAreNoFilingsAndSurvivesARefreshFailure() {
		org.mockito.Mockito.doThrow(new RuntimeException("edgar down")).when(filings).refresh(anyString());

		ResearchJob job = runSingleStep("FILINGS");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("No recent SEC filings"));
	}

	@Test
	void deepShowsThesisAtRiskEntryPriceAndTheTrackRecord() {
		DeepAnalysis d = deepDone(java.time.Duration.ofDays(3));
		d.recordEntry(100.0, 90.0);
		d.flagAtRisk("Price fell through the invalidation level.");
		when(deepAnalyses.latestDone("SPCX")).thenReturn(Optional.of(d));
		when(scorecard.trackRecord(DeepVerdict.WORTH_BUYING)).thenReturn(Optional.of(new com.argus.deepanalysis.TrackRecord(24, 0.58, 1.4, 30)));

		ResearchJob job = runSingleStep("DEEP");

		assertTrue(job.getFindings().contains("THESIS AT RISK: Price fell through the invalidation level."), job.getFindings());
		assertTrue(job.getFindings().contains("Price when analysed: 100") && job.getFindings().contains("proven wrong beyond 90"), job.getFindings());
		assertTrue(job.getFindings().contains("58% right against the S&P 500 over 24 matured calls"), job.getFindings());
	}

	@Test
	void aBrokenScorecardNeverBreaksTheDeepStep() {
		when(deepAnalyses.latestDone("SPCX")).thenReturn(Optional.of(deepDone(java.time.Duration.ofDays(3))));
		when(scorecard.trackRecord(any())).thenThrow(new RuntimeException("candles unavailable"));

		ResearchJob job = runSingleStep("DEEP");

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("Worth buying"));
	}
}
