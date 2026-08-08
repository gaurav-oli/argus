package com.argus.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentAnalysis;
import com.argus.intelligence.SentimentLabel;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Smart Cleanup agent against real Postgres — no prior automated coverage existed (verified
 * live/manually per the original build), so this locks in both the pre-existing dispose/keep/rollup
 * behavior and the two deferred enhancements added here: duplicate-story dedup in the daily rollup,
 * and precedent tagging of event-anchored rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CleanupServiceIntegrationTest {

	@Autowired
	CleanupService cleanup;

	@Autowired
	NewsArticleRepository articles;

	@Autowired
	JdbcTemplate jdbc;

	// Matches CleanupService's defaults (no test-profile override): keepRawDays=30, anchorDays=3.
	private static final Instant OLD = Instant.now().minus(40, ChronoUnit.DAYS);

	@BeforeEach
	void clean() {
		articles.deleteAll();
		jdbc.update("delete from sentiment_daily");
		jdbc.update("delete from cleanup_run");
		jdbc.update("delete from calendar_events");
	}

	private NewsArticle weakSignalArticle(String headline) {
		NewsArticle a = new NewsArticle("rss", "id-" + System.nanoTime(), "http://x", headline, "summary", OLD,
				new String[] { "ABCD" });
		a.applySentiment(new SentimentAnalysis(SentimentLabel.NEUTRAL, 0.1, 0.1, false), Instant.now());
		return a;
	}

	@Test
	void duplicateHeadlinesCollapseToOneStoryInTheDailyRollup() {
		// Three unanchored, weak-signal, old articles — individually each is a disposal candidate —
		// but they're the same wire story re-punctuated by three different feeds.
		articles.save(weakSignalArticle("ABCD wins major contract"));
		articles.save(weakSignalArticle("ABCD Wins Major Contract!"));
		articles.save(weakSignalArticle("abcd  wins major contract."));

		cleanup.run();

		Integer postCount = jdbc.queryForObject(
				"select post_count from sentiment_daily where kind = 'NEWS' and ticker = 'ABCD'"
						+ " and day = ?::date", Integer.class, Timestamp.from(OLD));
		assertEquals(1, postCount, "three near-duplicate copies of the same story must roll up as one");
		assertEquals(0, articles.count(), "every duplicate copy is still individually disposable and deleted");
	}

	@Test
	void distinctHeadlinesDoNotCollapse() {
		articles.save(weakSignalArticle("ABCD wins major contract"));
		articles.save(weakSignalArticle("ABCD announces new CFO"));

		cleanup.run();

		Integer postCount = jdbc.queryForObject(
				"select post_count from sentiment_daily where kind = 'NEWS' and ticker = 'ABCD'"
						+ " and day = ?::date", Integer.class, Timestamp.from(OLD));
		assertEquals(2, postCount, "genuinely distinct stories must not be collapsed");
	}

	@Test
	void eventAnchoredOldRowIsKeptAndTaggedPrecedent() {
		NewsArticle saved = articles.save(weakSignalArticle("ABCD faces regulatory probe"));
		seedCalendarEvent("ABCD", OLD.plus(1, ChronoUnit.DAYS)); // within the 3-day anchor window

		cleanup.run();

		assertTrue(articles.findById(saved.getId()).isPresent(), "anchored rows must survive cleanup");
		Boolean precedent = jdbc.queryForObject(
				"select is_precedent from news_articles where id = ?", Boolean.class, saved.getId());
		assertTrue(precedent, "an event-anchored row spared from cleanup must be tagged precedent");
	}

	@Test
	void unanchoredOldRowIsDeletedAndNeverTaggedPrecedent() {
		NewsArticle saved = articles.save(weakSignalArticle("ABCD quiet trading session"));

		cleanup.run();

		assertTrue(articles.findById(saved.getId()).isEmpty(), "unanchored, weak-signal, old rows are disposable");
		assertEquals(0, articles.count());
	}

	@Test
	void previewChangesNothing() {
		NewsArticle saved = articles.save(weakSignalArticle("ABCD preview target"));

		CleanupService.CleanupReport report = cleanup.preview();

		assertTrue(report.dryRun());
		assertTrue(articles.findById(saved.getId()).isPresent(), "preview must not delete anything");
		assertEquals(0L, (long) jdbc.queryForObject("select count(*) from sentiment_daily", Long.class),
				"preview must not roll up anything either");
	}

	private void seedCalendarEvent(String ticker, Instant at) {
		jdbc.update("insert into calendar_events (type, ticker, title, event_date, source, external_id, ingested_at)"
						+ " values ('EARNINGS', ?, 'test event', ?::date, 'test', ?, ?)",
				ticker, Timestamp.from(at), "ext-" + System.nanoTime(), Timestamp.from(at));
	}
}
