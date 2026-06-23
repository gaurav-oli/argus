package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.agent.AgentEventPublisher;
import com.argus.intelligence.NewsIngestionProperties.Gdelt;
import com.argus.intelligence.NewsIngestionProperties.Rss;
import com.argus.marketdata.MarketClock;
import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agent 1 ingestion: dedup, tagging, event emission, source-failure isolation, cadence gate. */
class NewsIngestionServiceTest {

	private final NewsArticleRepository articles = mock(NewsArticleRepository.class);
	private final PositionRepository positions = mock(PositionRepository.class);
	private final MarketClock clock = mock(MarketClock.class);
	private final AgentEventPublisher events = mock(AgentEventPublisher.class);
	private final SourceCredibilityService credibility = mock(SourceCredibilityService.class);
	private final TickerRelevanceTagger tagger = new TickerRelevanceTagger();

	private final NewsIngestionProperties props = new NewsIngestionProperties(
			60_000, 300_000, 900_000, 60, new Gdelt(true, "q", 10), new Rss(List.of()));

	private static RawArticle raw(String source, String id, String headline) {
		return new RawArticle(source, id, "http://x/" + id, headline, null, Instant.now(), List.of());
	}

	private void heldAapl() {
		Position pos = mock(Position.class);
		lenient().when(pos.getTicker()).thenReturn("AAPL");
		when(positions.findAllByOrderByTickerAsc()).thenReturn(List.of(pos));
	}

	private NewsIngestionService service(List<NewsSource> sources) {
		return new NewsIngestionService(sources, articles, tagger, positions, clock, events,
				credibility, props);
	}

	@Test
	void storesNewArticlesTagsThemAndEmitsEvents() {
		heldAapl();
		NewsSource src = mock(NewsSource.class);
		when(src.name()).thenReturn("good");
		when(src.fetch(any())).thenReturn(List.of(
				raw("good", "1", "AAPL jumps on earnings"),
				raw("good", "2", "Unrelated market wrap")));
		when(articles.existsBySourceAndExternalId(eq("good"), anyString())).thenReturn(false);
		when(articles.save(any())).thenAnswer(inv -> persisted(inv.getArgument(0)));

		int stored = service(List.of(src)).ingestOnce();

		assertEquals(2, stored);
		verify(articles, times(2)).save(any(NewsArticle.class));
		verify(events, times(2)).publish(eq(NewsIngestionService.STREAM_KEY),
				eq("news.article.ingested"), any());
	}

	@Test
	void skipsDuplicatesByNaturalKey() {
		heldAapl();
		NewsSource src = mock(NewsSource.class);
		when(src.name()).thenReturn("good");
		when(src.fetch(any())).thenReturn(List.of(raw("good", "1", "AAPL news")));
		when(articles.existsBySourceAndExternalId("good", "1")).thenReturn(true);

		int stored = service(List.of(src)).ingestOnce();

		assertEquals(0, stored);
		verify(articles, times(0)).save(any());
		verify(events, times(0)).publish(anyString(), anyString(), any());
	}

	@Test
	void oneFailingSourceDoesNotAbortTheCycle() {
		heldAapl();
		NewsSource bad = mock(NewsSource.class);
		when(bad.name()).thenReturn("bad");
		when(bad.fetch(any())).thenThrow(new RuntimeException("boom"));
		NewsSource good = mock(NewsSource.class);
		when(good.name()).thenReturn("good");
		when(good.fetch(any())).thenReturn(List.of(raw("good", "1", "AAPL news")));
		when(articles.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);
		when(articles.save(any())).thenAnswer(inv -> persisted(inv.getArgument(0)));

		int stored = service(List.of(bad, good)).ingestOnce();

		assertEquals(1, stored);
	}

	@Test
	void cadenceGateSkipsSecondTickWithinTheInterval() {
		heldAapl();
		when(clock.isRegularHours(any())).thenReturn(true); // 5-min regular cadence
		NewsSource src = mock(NewsSource.class);
		lenient().when(src.name()).thenReturn("good");
		when(src.fetch(any())).thenReturn(List.of());
		NewsIngestionService service = service(List.of(src));

		service.scheduledTick(); // first ever → runs
		service.scheduledTick(); // immediately after → gated out

		verify(src, times(1)).fetch(any());
	}

	/** Mirror what JPA does on persist: assign an id so the emitted event payload is complete. */
	private static NewsArticle persisted(NewsArticle in) {
		NewsArticle saved = mock(NewsArticle.class);
		lenient().when(saved.getId()).thenReturn(42L);
		lenient().when(saved.getSource()).thenReturn(in.getSource());
		lenient().when(saved.getHeadline()).thenReturn(in.getHeadline());
		lenient().when(saved.getPublishedAt()).thenReturn(in.getPublishedAt());
		return saved;
	}
}
