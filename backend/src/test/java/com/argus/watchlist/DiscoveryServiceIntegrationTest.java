package com.argus.watchlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The discovered watchlist tracks what is trending NOW: a still-trending pick is refreshed, a new one
 * gets in, and one that stopped trending is wound down instead of holding its slot for days.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DiscoveryServiceIntegrationTest {

	@Autowired
	DiscoveryService discovery;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("delete from watchlist");
		jdbc.update("delete from social_posts");
	}

	private void mentions(String ticker, int n) {
		for (int i = 0; i < n; i++) {
			jdbc.update("insert into social_posts (ticker, source, external_id, body, posted_at) values (?, 'test', ?, ?, now())",
					ticker, ticker + "-" + i + "-" + System.nanoTime(), "watching $" + ticker + " today");
		}
	}

	private void discovered(String ticker, String note, Instant expires) {
		jdbc.update("insert into watchlist (ticker, source, note, active, expires_at) values (?, 'DISCOVERED', ?, true, ?)",
				ticker, note, java.sql.Timestamp.from(expires));
	}

	private Instant expiry(String ticker) {
		return jdbc.queryForObject("select expires_at from watchlist where ticker = ?", java.sql.Timestamp.class, ticker).toInstant();
	}

	@Test
	void aStillTrendingPickIsRefreshedNotLeftToExpire() {
		discovered("PLTR", "Trending: 12 social mentions", Instant.now().plus(Duration.ofHours(6)));
		mentions("PLTR", 30);

		discovery.discover();

		assertTrue(expiry("PLTR").isAfter(Instant.now().plus(Duration.ofDays(2))), "expiry pushed out again");
		assertEquals("Trending: 30 social mentions",
				jdbc.queryForObject("select note from watchlist where ticker = 'PLTR'", String.class), "mention count is current");
	}

	@Test
	void aPickThatStoppedTrendingIsWoundDownAndANewOneGetsIn() {
		discovered("OLDY", "Trending: 99 social mentions", Instant.now().plus(Duration.ofDays(3)));
		mentions("NEWB", 25); // trending now; OLDY has no recent mentions

		discovery.discover();

		assertTrue(expiry("OLDY").isBefore(Instant.now().plus(Duration.ofHours(DiscoveryService.NOT_TRENDING_GRACE_HOURS + 1))),
				"no longer trending: short grace, not the full three days");
		assertEquals(1, jdbc.queryForObject("select count(*) from watchlist where ticker = 'NEWB' and source = 'DISCOVERED'", Integer.class));
	}

	@Test
	void aManualPickIsNeverTouched() {
		jdbc.update("insert into watchlist (ticker, source, note, active) values ('MANU', 'MANUAL', 'mine', false)");
		mentions("MANU", 40);

		discovery.discover();

		assertEquals("MANUAL", jdbc.queryForObject("select source from watchlist where ticker = 'MANU'", String.class));
		assertEquals(Boolean.FALSE, jdbc.queryForObject("select active from watchlist where ticker = 'MANU'", Boolean.class));
	}
}
