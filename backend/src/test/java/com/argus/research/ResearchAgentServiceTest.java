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
import com.argus.common.BadRequestException;
import com.argus.common.LivePushService;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.internet.WebMentionRepository;
import com.argus.marketdata.FinnhubRest;
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
	private final FinnhubRest finnhub = mock(FinnhubRest.class);

	private final ResearchAgentService service = new ResearchAgentService(
			jobs, news, social, sec, web, calendar, gateway, livePush, props, finnhub, "test-key");

	{
		// Default every raw-data source to empty unless a test overrides it — keeps each test focused
		// on what it's actually exercising.
		when(news.findAnalyzedForTicker(anyString(), any())).thenReturn(List.of());
		when(social.findByTickerAndPostedAtAfter(anyString(), any())).thenReturn(List.of());
		when(sec.findByTickerAndTransactionTypeInAndFiledAtAfter(anyString(), any(), any())).thenReturn(List.of());
		when(web.findByTickerAndPostedAtAfter(anyString(), any())).thenReturn(List.of());
		when(calendar.findByTickerAndTypeAndEventDateBetweenOrderByEventDateAsc(anyString(), any(), any(), any()))
				.thenReturn(List.of());
		when(finnhub.get(anyString())).thenReturn(Optional.empty());
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

	@Test
	void gatherFinancialsSummarizesKeyRatiosFromFinnhub() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"Ratios\",\"dataSource\":\"FINANCIALS\",\"why\":\"x\"}]");
		when(finnhub.get(contains("stock/metric"))).thenReturn(
				Optional.of("{\"metric\":{\"peTTM\":25.4,\"netProfitMarginTTM\":12.3}}"));
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("25.4"), "the P/E ratio must appear in the findings");
		assertTrue(job.getFindings().contains("12.3"), "the net margin must appear in the findings");
	}

	@Test
	void gatherFinancialsDegradesGracefullyWithNoApiKey() {
		ResearchAgentService noKeyService = new ResearchAgentService(
				jobs, news, social, sec, web, calendar, gateway, livePush, props, finnhub, "");
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"Ratios\",\"dataSource\":\"FINANCIALS\",\"why\":\"x\"}]");
		when(gateway.escalate(anyString())).thenReturn("report");

		noKeyService.runPipeline(1L);

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("No Finnhub API key configured"));
		verify(finnhub, never()).get(anyString());
	}

	@Test
	void gatherFinancialsDegradesGracefullyWhenFinnhubReturnsNothing() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(contains("Propose an ordered research plan"), eq(ModelTier.BIG)))
				.thenReturn("[{\"label\":\"Ratios\",\"dataSource\":\"FINANCIALS\",\"why\":\"x\"}]");
		when(finnhub.get(contains("stock/metric"))).thenReturn(Optional.empty());
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		assertEquals(ResearchJob.Status.DONE, job.getStatus());
		assertTrue(job.getFindings().contains("unavailable"));
	}

	@Test
	void planFallsBackToTheDefaultWhenTheModelCallFails() {
		ResearchJob job = new ResearchJob("SPCX");
		when(jobs.findById(1L)).thenReturn(Optional.of(job));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenThrow(new RuntimeException("model down"));
		when(gateway.escalate(anyString())).thenReturn("report");

		service.runPipeline(1L);

		List<Step> finalPlan = ResearchAgentService.readPlan(job.getPlan());
		assertEquals(7, finalPlan.size(), "the default (all 7 sources) plan is used when planning fails");
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
}
