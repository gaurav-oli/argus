package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.agent.AgentEventPublisher;
import com.argus.intelligence.MarketDataPort.MarketStats;
import com.argus.push.PushService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Stranger detection orchestration: thresholding, known-universe filtering, notify-once (Story 4.4). */
class StrangerDangerServiceTest {

	private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
	private final SourceCredibilityRepository credibility = mock(SourceCredibilityRepository.class);
	private final StrangerAlertRepository alerts = mock(StrangerAlertRepository.class);
	private final KnownUniverse knownUniverse = mock(KnownUniverse.class);
	@SuppressWarnings("unchecked")
	private final ObjectProvider<MarketDataPort> marketData = mock(ObjectProvider.class);
	private final AgentEventPublisher events = mock(AgentEventPublisher.class);
	private final PushService push = mock(PushService.class);
	private final StrangerDangerProperties props = new StrangerDangerProperties(60000, 360, 3, 6);

	private final StrangerDangerService service = new StrangerDangerService(articles, new CashtagExtractor(),
			knownUniverse, credibility, new StrangerRiskScorer(), alerts, marketData, events, props, push);

	private static NewsArticle article(String source, String id, String headline) {
		return new NewsArticle(source, id, "http://x/" + id, headline, null, Instant.now(), new String[0]);
	}

	private void recent(NewsArticle... a) {
		when(articles.findByPublishedAtAfterOrderByPublishedAtDesc(any())).thenReturn(List.of(a));
		lenient().when(credibility.findBySource(anyString())).thenReturn(Optional.empty());
		lenient().when(marketData.getIfAvailable()).thenReturn(null);
		lenient().when(alerts.save(any())).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void flagsStrangerOverThresholdAndEmitsSignal() {
		when(knownUniverse.knownTickers()).thenReturn(Set.of("AAPL"));
		recent(article("rss", "1", "$ABCD to the moon"),
				article("blog", "2", "$ABCD squeeze incoming"),
				article("x", "3", "everyone buying $ABCD"));
		when(alerts.findByTicker("ABCD")).thenReturn(Optional.empty());

		assertEquals(1, service.scan());
		verify(alerts).save(any(StrangerAlert.class));
		verify(events).publish(eq(StrangerDangerService.STREAM_KEY), eq("stranger.detected"), any());
	}

	@Test
	void doesNotFlagKnownTicker() {
		when(knownUniverse.knownTickers()).thenReturn(Set.of("AAPL"));
		recent(article("rss", "1", "$AAPL up"), article("blog", "2", "$AAPL strong"),
				article("x", "3", "$AAPL beats"));

		assertEquals(0, service.scan());
		verify(alerts, never()).save(any());
		verify(events, never()).publish(anyString(), anyString(), any());
	}

	@Test
	void doesNotFlagBelowCoverageThreshold() {
		when(knownUniverse.knownTickers()).thenReturn(Set.of());
		recent(article("rss", "1", "$ABCD news"), article("blog", "2", "$ABCD again")); // 2 < 3

		assertEquals(0, service.scan());
		verify(events, never()).publish(anyString(), anyString(), any());
	}

	@Test
	void existingAlertIsRefreshedNotReEmitted() {
		when(knownUniverse.knownTickers()).thenReturn(Set.of());
		recent(article("rss", "1", "$ABCD news"), article("blog", "2", "$ABCD again"),
				article("x", "3", "$ABCD more"));
		StrangerAlert existing = new StrangerAlert("ABCD",
				new StrangerAssessment(3, 3, 35.0, 40), 6, Instant.now());
		when(alerts.findByTicker("ABCD")).thenReturn(Optional.of(existing));

		assertEquals(0, service.scan(), "re-detection is not a new detection");
		verify(alerts).save(existing);
		verify(events, never()).publish(anyString(), anyString(), any());
	}

	@Test
	void marketDataPortIsUsedWhenPresent() {
		when(knownUniverse.knownTickers()).thenReturn(Set.of());
		recent(article("rss", "1", "$ABCD news"), article("blog", "2", "$ABCD again"),
				article("x", "3", "$ABCD more"));
		when(alerts.findByTicker("ABCD")).thenReturn(Optional.empty());
		MarketDataPort port = mock(MarketDataPort.class);
		when(marketData.getIfAvailable()).thenReturn(port);
		when(port.statsFor("ABCD")).thenReturn(
				Optional.of(new MarketStats(java.math.BigDecimal.valueOf(50_000_000L), 9_000_000, 1_000_000)));

		service.scan();

		verify(port, times(1)).statsFor("ABCD");
	}
}
