package com.argus.intelligence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.cost.CostGovernor;
import com.argus.model.ModelGateway;
import com.argus.notification.NotificationPreferencesService;
import com.argus.push.PushService;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Breaking-news detection (Story 8.x) — in particular that macro/policy topics (shared with
 * {@link MacroRelevanceTagger}) and holdings-material stories both fire, and that the currency/FX
 * blind spot found in production (a yen-intervention story matched nothing, on either this path or
 * the trading-signal path) is actually closed.
 */
class BreakingNewsAlertServiceTest {

	private final BreakingAlertRepository alerts = mock(BreakingAlertRepository.class);
	private final PushService push = mock(PushService.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final CostGovernor costGovernor = mock(CostGovernor.class);
	private final NotificationPreferencesService prefs = mock(NotificationPreferencesService.class);
	private final MacroRelevanceTagger macroTagger = new MacroRelevanceTagger();

	// llmConfirm off by default so tests exercise the deterministic gate, not the LLM confirm step.
	private final BreakingNewsProperties props =
			new BreakingNewsProperties(true, 0.5, 0.45, 4, 180, 6, false);

	private final BreakingNewsAlertService service =
			new BreakingNewsAlertService(alerts, push, gateway, costGovernor, prefs, props, macroTagger);

	{
		when(prefs.allow(any(), any(), anyBoolean())).thenReturn(true);
		when(alerts.existsByHeadlineAndCreatedAtAfter(anyString(), any())).thenReturn(false);
		when(alerts.countByCreatedAtAfter(any())).thenReturn(0L);
	}

	private static NewsArticle article(String headline, SentimentLabel label, double score, double relevance) {
		NewsArticle a = new NewsArticle("Reuters", "id-" + Math.random(), "u", headline, null,
				Instant.now(), new String[0]);
		a.applySentiment(new SentimentAnalysis(label, score, relevance, false), Instant.now());
		return a;
	}

	@Test
	void yenInterventionStoryNowAlertsEvenWithNoHoldingsImpact() {
		// The exact gap found in production: no ticker relevance, low holdings impact, but a real
		// market-moving currency story — previously matched neither this list nor the signal path.
		NewsArticle a = article("Yen weakens further as US signals support for intervention",
				SentimentLabel.BEARISH, -0.5, 0.1);

		service.evaluate(a);

		verify(alerts, times(1)).save(any());
	}

	@Test
	void trumpTariffStoryAlertsViaTheSharedMacroPath() {
		NewsArticle a = article("Trump announces new tariffs on Chinese imports",
				SentimentLabel.BEARISH, -0.6, 0.0);

		service.evaluate(a);

		verify(alerts, times(1)).save(any());
	}

	@Test
	void crisisTopicStillAlertsViaTheServicesOwnList() {
		NewsArticle a = article("Market plunge triggers circuit breaker on Wall Street",
				SentimentLabel.BEARISH, -0.5, 0.0);

		service.evaluate(a);

		verify(alerts, times(1)).save(any());
	}

	@Test
	void highImpactHoldingsStoryAlertsWithNoTopicMatchAtAll() {
		NewsArticle a = article("AAPL beats quarterly earnings estimates by wide margin",
				SentimentLabel.BULLISH, 0.9, 0.8); // impact 0.72 ≥ threshold 0.5

		service.evaluate(a);

		verify(alerts, times(1)).save(any());
	}

	@Test
	void lowImpactOrdinaryStoryDoesNotAlert() {
		NewsArticle a = article("Regional retailer opens new store location",
				SentimentLabel.NEUTRAL, 0.05, 0.1);

		service.evaluate(a);

		verify(alerts, never()).save(any());
	}

	@Test
	void macroStoryBelowSentimentFloorDoesNotAlert() {
		// Topic matches (tariff) but sentiment is too mild to clear sentimentMin (0.45).
		NewsArticle a = article("Analysts discuss long-term tariff policy implications",
				SentimentLabel.BEARISH, -0.1, 0.0);

		service.evaluate(a);

		verify(alerts, never()).save(any());
	}

	@Test
	void disabledPropertySkipsEvaluationEntirely() {
		BreakingNewsProperties off = new BreakingNewsProperties(false, 0.5, 0.45, 4, 180, 6, false);
		BreakingNewsAlertService disabled =
				new BreakingNewsAlertService(alerts, push, gateway, costGovernor, prefs, off, macroTagger);
		NewsArticle a = article("Trump announces new tariffs", SentimentLabel.BEARISH, -0.6, 0.0);

		disabled.evaluate(a);

		verify(alerts, never()).save(any());
	}
}
