package com.argus;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers for integration tests: Postgres 18 (pgvector) and Redis 8.
 * {@code @ServiceConnection} auto-wires the datasource/redis connection properties,
 * so tests need no manual configuration and never depend on a running docker compose stack.
 * Requires the Docker daemon to be running.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		return new PostgreSQLContainer<>(
				DockerImageName.parse("pgvector/pgvector:0.8.2-pg18")
						.asCompatibleSubstituteFor("postgres"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:8"))
				.withExposedPorts(6379);
	}
}
