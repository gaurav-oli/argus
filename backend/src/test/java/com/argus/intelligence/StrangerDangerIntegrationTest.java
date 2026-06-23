package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Stranger Danger end-to-end against real Postgres + Redis (Story 4.4): ingested cashtag coverage of
 * a non-held ticker produces a persisted alert with the elevated consensus bar and a signal on the
 * stranger stream; a second scan refreshes without re-signalling. The known universe is mocked to a
 * fixed holdings set.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class StrangerDangerIntegrationTest {

	@Autowired
	StrangerDangerService service;

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	StrangerAlertRepository alerts;

	@Autowired
	StringRedisTemplate redis;

	@MockitoBean
	KnownUniverse knownUniverse;

	@BeforeEach
	void clean() {
		alerts.deleteAll();
		articles.deleteAll();
		redis.delete(StrangerDangerService.STREAM_KEY);
		org.mockito.Mockito.when(knownUniverse.knownTickers()).thenReturn(java.util.Set.of("AAPL"));
	}

	private void ingest(String source, String id, String headline) {
		articles.save(new NewsArticle(source, id, "http://x/" + id, headline, null,
				Instant.now(), new String[0]));
	}

	@Test
	void flagsStrangerPersistsAlertAndSignals() {
		ingest("rss", "1", "$ZZZP rockets 200%");
		ingest("blog", "2", "everyone piling into $ZZZP");
		ingest("x", "3", "$ZZZP short squeeze");
		ingest("y", "4", "$AAPL steady"); // known — must not flag

		int flagged = service.scan();

		assertEquals(1, flagged);
		StrangerAlert alert = alerts.findByTicker("ZZZP").orElseThrow();
		assertEquals(3, alert.getCoverageCount());
		assertEquals(6, alert.getRequiredConsensus());
		assertTrue(alert.getRiskScore() >= 0 && alert.getRiskScore() <= 100);
		assertTrue(alerts.findByTicker("AAPL").isEmpty(), "known ticker must not be flagged");

		List<MapRecord<String, Object, Object>> signals = redis.opsForStream()
				.read(StreamOffset.fromStart(StrangerDangerService.STREAM_KEY));
		assertEquals(1, signals.size());
		assertTrue(String.valueOf(signals.get(0).getValue()).contains("stranger.detected"));
	}

	@Test
	void secondScanRefreshesWithoutReSignalling() {
		ingest("rss", "1", "$ZZZP rockets");
		ingest("blog", "2", "$ZZZP squeeze");
		ingest("x", "3", "$ZZZP buy");

		assertEquals(1, service.scan());
		assertEquals(0, service.scan(), "already-flagged stranger is not a new detection");

		List<MapRecord<String, Object, Object>> signals = redis.opsForStream()
				.read(StreamOffset.fromStart(StrangerDangerService.STREAM_KEY));
		assertEquals(1, signals.size(), "signal emitted only on first detection");
	}
}
