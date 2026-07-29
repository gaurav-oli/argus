package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.model.ModelGateway;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Breaking-alert curation (dedup + summarize in one Gemma call) against real Postgres: a DUPLICATE
 * verdict never surfaces in the queue, a real summary does, and a model failure falls back to a
 * deterministic paragraph rather than either dropping the alert or wrongly marking it a duplicate.
 * The model is mocked at the gateway boundary so verdicts are deterministic.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class BreakingAlertCurationServiceTest {

	@Autowired
	BreakingAlertRepository alerts;

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	BreakingAlertCurationService curation;

	@MockitoBean
	ModelGateway gateway;

	@BeforeEach
	void clean() {
		alerts.deleteAll();
		articles.deleteAll();
	}

	private BreakingAlert pending(String headline) {
		return alerts.save(new BreakingAlert(headline, "http://x", new String[] {"AAPL"},
				"Breaking: test", 0.7, "BULLISH", null));
	}

	@Test
	void writesAGeneratedSummaryWhenTheModelRespondsWithOne() {
		when(gateway.generate(anyString()))
				.thenReturn("What happened.\n\nWhy it matters.\n\nMarket impact.\n\nKEY TERMS:\nTerm — def");
		BreakingAlert alert = pending("A genuinely new story");

		curation.generateNextPending();

		BreakingAlert reloaded = alerts.findById(alert.getId()).orElseThrow();
		assertTrue(reloaded.getSummary().contains("Why it matters."));
		assertFalse(reloaded.isDuplicate());
		assertFalse(reloaded.isFallback());
	}

	@Test
	void marksDuplicateWhenTheModelSaysSoAndNeverExposesASummary() {
		when(gateway.generate(anyString())).thenReturn("DUPLICATE");
		BreakingAlert alert = pending("Same story, reworded");

		curation.generateNextPending();

		BreakingAlert reloaded = alerts.findById(alert.getId()).orElseThrow();
		assertTrue(reloaded.isDuplicate());
		assertNull(reloaded.getSummary(), "a duplicate must never get a summary");
	}

	@Test
	void modelFailureFallsBackAndDoesNotMarkDuplicate() {
		when(gateway.generate(anyString())).thenThrow(new RuntimeException("model down"));
		BreakingAlert alert = pending("Important story the model failed to summarize");

		curation.generateNextPending();

		BreakingAlert reloaded = alerts.findById(alert.getId()).orElseThrow();
		assertFalse(reloaded.isDuplicate(), "a failure must never silently hide a possibly-important alert");
		assertTrue(reloaded.isFallback());
		assertTrue(reloaded.getSummary().contains("Important story the model failed to summarize"));
	}

	@Test
	void alreadyCoveredHeadlinesAreIncludedInThePromptContext() {
		BreakingAlert covered = pending("Apple hits new high");
		covered.summarize("Earlier summary.\n\nKEY TERMS:\nNone", false);
		alerts.save(covered);

		when(gateway.generate(anyString())).thenAnswer(inv -> {
			String prompt = inv.getArgument(0);
			assertTrue(prompt.contains("Apple hits new high"), "the prompt must list already-covered headlines");
			return "New paragraph.\n\nKEY TERMS:\nNone";
		});
		pending("A different, new story");

		curation.generateNextPending();
	}

	@Test
	void articleSnippetIsIncludedWhenTheAlertHasAnArticleId() {
		NewsArticle article = articles.save(new NewsArticle("finnhub", "ext-1", "http://x",
				"Original headline", "the original article snippet", Instant.now(), new String[] {"AAPL"}));
		BreakingAlert alert = alerts.save(new BreakingAlert("Original headline", "http://x",
				new String[] {"AAPL"}, "Breaking: test", 0.7, "BULLISH", article.getId()));

		when(gateway.generate(anyString())).thenAnswer(inv -> {
			String prompt = inv.getArgument(0);
			assertTrue(prompt.contains("the original article snippet"));
			return "Summary.\n\nKEY TERMS:\nNone";
		});

		curation.generateNextPending();

		assertTrue(alerts.findById(alert.getId()).orElseThrow().getSummary().contains("Summary."));
	}
}
