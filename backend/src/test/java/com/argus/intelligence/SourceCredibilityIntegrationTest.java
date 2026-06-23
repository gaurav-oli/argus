package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.notification.NotificationStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Credibility engine against real Postgres + Redis (Story 4.3): score/tier/blocked persist through
 * the enum-text column, and an auto-block emits a notification event on the notifications stream.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class SourceCredibilityIntegrationTest {

	@Autowired
	SourceCredibilityService service;

	@Autowired
	SourceCredibilityRepository repo;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void clean() {
		repo.deleteAll();
		redis.delete(NotificationStream.KEY);
	}

	@Test
	void registersUnknownAtBronze35() {
		SourceCredibility c = service.register("reuters.com");
		assertEquals(35, c.getScore());
		assertEquals(CredibilityTier.BRONZE, c.getTier());
		assertFalse(c.isBlocked());
		// Re-register is idempotent (no duplicate row).
		service.register("reuters.com");
		assertEquals(1, repo.count());
	}

	@Test
	void outcomePersistsScoreAndTier() {
		service.register("blog.example");
		service.recordOutcome("blog.example", true); // 37

		SourceCredibility reloaded = repo.findBySource("blog.example").orElseThrow();
		assertEquals(37, reloaded.getScore());
		assertEquals(CredibilityTier.BRONZE, reloaded.getTier());
		assertEquals(1, reloaded.getCorrectCount());
	}

	@Test
	void autoBlockPersistsAndEmitsNotification() {
		for (int i = 0; i < 9; i++) {
			service.recordOutcome("pump.news", false); // 35 → 8
		}

		SourceCredibility reloaded = repo.findBySource("pump.news").orElseThrow();
		assertTrue(reloaded.isBlocked());
		assertEquals(CredibilityTier.BLOCKED, reloaded.getTier());
		assertTrue(service.isBlocked("pump.news"));

		List<MapRecord<String, Object, Object>> records = redis.opsForStream()
				.read(StreamOffset.fromStart(NotificationStream.KEY));
		assertEquals(1, records.size(), "exactly one auto-block notification should be emitted");
		assertTrue(String.valueOf(records.get(0).getValue()).contains("source.auto_blocked"));
	}
}
