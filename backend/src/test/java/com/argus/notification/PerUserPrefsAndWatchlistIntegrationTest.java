package com.argus.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.notification.NotificationPreferencesService.Category;
import com.argus.notification.NotificationPreferencesService.View;
import com.argus.security.CurrentUserContext;
import com.argus.watchlist.CompositeKnownUniverse;
import com.argus.watchlist.WatchlistEntry;
import com.argus.watchlist.WatchlistRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** S-C1: one friend's notification preferences and watchlist picks never rewrite anyone else's. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PerUserPrefsAndWatchlistIntegrationTest {

	@Autowired
	NotificationPreferencesService prefs;

	@Autowired
	WatchlistRepository watchlist;

	@Autowired
	CompositeKnownUniverse universe;

	@Autowired
	JdbcTemplate jdbc;

	long ana;
	long ben;

	private long user(String email) {
		jdbc.update("delete from app_user where email = ?", email);
		return jdbc.queryForObject("insert into app_user (google_sub, email, name) values (?, ?, ?) returning id", Long.class,
				"sub-" + email, email, email);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("delete from watchlist where ticker in ('ZZA', 'ZZB', 'ZZD')");
		ana = user("ana-prefs@example.test");
		ben = user("ben-prefs@example.test");
	}

	@Test
	void eachPersonHasTheirOwnPreferences() {
		CurrentUserContext.runAs(ana, () -> prefs.update(new View(true, false, true, null, null, List.of("tsla"))));

		assertFalse(prefs.allowFor(ana, Category.BREAKING, null, false), "ana turned breaking off");
		assertTrue(prefs.allowFor(ben, Category.BREAKING, null, false), "ben's defaults are untouched");
		assertFalse(prefs.allowFor(ana, Category.ALERT, new String[] {"TSLA"}, true), "ana muted TSLA");
		assertTrue(prefs.allowFor(ben, Category.ALERT, new String[] {"TSLA"}, true));
		assertEquals(List.of("TSLA"), CurrentUserContext.callAs(ana, prefs::current).mutedTickers());
		assertTrue(CurrentUserContext.callAs(ben, prefs::current).breakingEnabled());
	}

	@Test
	void watchlistPicksArePerPersonButCoverageIsTheUnion() {
		watchlist.save(WatchlistEntry.manualFor(ana, "ZZA", "ana's pick"));
		watchlist.save(WatchlistEntry.manualFor(ben, "ZZB", "ben's pick"));
		watchlist.save(WatchlistEntry.manualFor(ben, "ZZA", "ben likes it too"));
		watchlist.save(new WatchlistEntry("ZZD", WatchlistEntry.Source.DISCOVERED, "trending", Instant.now().plusSeconds(3600)));

		List<String> anaSees = watchlist.visibleTo(ana).stream().map(WatchlistEntry::getTicker).filter(t -> t.startsWith("ZZ")).sorted().toList();
		assertEquals(List.of("ZZA", "ZZD"), anaSees, "own pick + discoveries, not ben's ZZB");

		assertEquals(1, watchlist.deleteOwn("ZZA", ana));
		assertEquals(0, watchlist.deleteOwn("ZZB", ana), "ana cannot remove ben's pick");
		assertTrue(watchlist.findByTickerAndUserId("ZZA", ben).isPresent(), "ben's own ZZA survives ana's removal");

		assertTrue(universe.knownTickers().containsAll(List.of("ZZA", "ZZB", "ZZD")), "the agents still cover everyone's picks");
	}
}
