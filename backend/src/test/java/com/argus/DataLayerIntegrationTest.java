package com.argus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the data layer is wired: Flyway baseline applied, pgvector present,
 * and Redis reachable — all against throwaway Testcontainers (Docker required).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DataLayerIntegrationTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	RedisConnectionFactory redisConnectionFactory;

	@Test
	void flywayBaselineApplied() {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '1'", Integer.class);
		assertEquals(1, count, "Flyway baseline V1 should be recorded exactly once");
	}

	@Test
	void pgvectorExtensionAvailable() {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
		assertEquals(1, count, "pgvector extension should be installed by the baseline migration");
	}

	@Test
	void redisRespondsToPing() {
		String pong = redisConnectionFactory.getConnection().ping();
		assertTrue("PONG".equalsIgnoreCase(pong), "Redis should respond to PING with PONG");
	}
}
