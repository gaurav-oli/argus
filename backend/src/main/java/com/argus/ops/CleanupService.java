package com.argus.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Smart Cleanup agent. Rather than deleting by age, it keeps raw firehose data by its <em>future
 * linking value</em>. A raw row survives if it is:
 * <ul>
 *   <li><b>recent</b> — within {@code keep-raw-days} (fresh detail the live agents still read);</li>
 *   <li><b>event-anchored</b> — near a real event for its ticker (a recommendation, paper trade,
 *       stranger-danger spike, or calendar event within {@code anchor-days}) — a precedent worth
 *       keeping to recognise similar setups later; or</li>
 *   <li><b>a strong signal</b> — high relevance / sentiment, a notable exemplar.</li>
 * </ul>
 * Everything else is routine old chatter with no precedent value. Before deleting it, the agent
 * <em>rolls it up</em> into {@code sentiment_daily} (one row per ticker/day with counts + sentiment
 * sums), so the historical baseline survives at a fraction of the size — even for tickers you hold —
 * and future events can still be compared against it.
 *
 * <p>It only ever touches the raw firehose ({@code social_posts}, {@code web_mentions},
 * {@code news_articles}); the recommendation/tuning corpus, open trades, pending calls, your portfolio,
 * and any {@code news_articles} still backing an active reader card are never deleted. On demand only:
 * {@link #preview()} computes the full keep/delete/roll-up plan and changes nothing; {@link #run()}
 * performs the roll-up then the delete in one transaction. Every run is logged to {@code cleanup_run}.
 */
@Service
public class CleanupService {

	private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** events(ticker, at): real events raw data can anchor to. Anchored data is precedent — kept. */
	private static final String EVENTS = """
			events(ticker, at) as (
			  select ticker, created_at from recommendations where ticker is not null
			  union all select ticker, entry_at from simulated_trades where ticker is not null
			  union all select ticker, detected_at from stranger_alerts where ticker is not null
			  union all select ticker, ingested_at from calendar_events where ticker is not null)""";

	private final JdbcTemplate jdbc;
	private final int keepRawDays;
	private final int anchorDays;
	private final double relevanceKeep;
	private final double sentimentKeep;

	public CleanupService(JdbcTemplate jdbc,
			@Value("${argus.cleanup.keep-raw-days:30}") int keepRawDays,
			@Value("${argus.cleanup.anchor-days:3}") int anchorDays,
			@Value("${argus.cleanup.relevance-keep:0.60}") double relevanceKeep,
			@Value("${argus.cleanup.sentiment-keep:0.60}") double sentimentKeep) {
		this.jdbc = jdbc;
		this.keepRawDays = keepRawDays;
		this.anchorDays = anchorDays;
		this.relevanceKeep = relevanceKeep;
		this.sentimentKeep = sentimentKeep;
	}

	public record SourceReport(String table, String kind, long rowsTotal, long affected, long keptRecent,
			long keptAnchored, long rollupDays, long freedBytes) {
	}

	public record CleanupReport(boolean dryRun, Instant startedAt, Instant finishedAt, long deletedRows,
			long keptRows, long rolledUpDays, long freedBytes, List<SourceReport> sources, String summary) {
	}

	/** Compute the full keep/delete/roll-up plan without changing anything. */
	public CleanupReport preview() {
		return execute(true);
	}

	/** Roll up then delete the disposable firehose rows, in one transaction. */
	@Transactional
	public CleanupReport run() {
		return execute(false);
	}

	private CleanupReport execute(boolean dryRun) {
		Instant started = Instant.now();
		List<SourceReport> sources = new ArrayList<>();
		sources.add(process(new Source("social_posts", "SOCIAL", "posted_at", "sp", socialCandidate("sp")), dryRun));
		sources.add(process(new Source("web_mentions", "WEB", "posted_at", "wm", webCandidate("wm")), dryRun));
		sources.add(process(new Source("news_articles", "NEWS", "published_at", "na", newsCandidate("na")), dryRun));

		long deleted = sources.stream().mapToLong(SourceReport::affected).sum();
		long rollupDays = sources.stream().mapToLong(SourceReport::rollupDays).sum();
		long freed = sources.stream().mapToLong(SourceReport::freedBytes).sum();
		long kept = sources.stream().mapToLong(s -> s.rowsTotal() - s.affected()).sum();
		String summary = summarize(dryRun, deleted, kept, rollupDays, freed);
		CleanupReport report = new CleanupReport(dryRun, started, Instant.now(), deleted, kept, rollupDays, freed,
				sources, summary);
		persist(report);
		log.info("Cleanup {}: {}", dryRun ? "preview" : "run", summary);
		return report;
	}

	/** One firehose table: count the plan, and (live only) roll up then delete its disposable rows. */
	private SourceReport process(Source s, boolean dryRun) {
		long rowsTotal = count("select count(*) from " + s.table());
		long wouldDelete = count(cte("select count(*) from " + s.table() + " " + s.alias() + " where " + s.candidate()));
		long keptRecent = count("select count(*) from " + s.table() + " " + s.alias() + " where " + s.withinWindow());
		long keptAnchored = count(cte("select count(*) from " + s.table() + " " + s.alias() + " where "
				+ s.olderThanCutoff() + " and " + s.anchored()));
		long rollupDays = count(cte("select count(*) from (" + s.distinctTickerDay() + ") x"));
		long size = count("select pg_total_relation_size('" + s.table() + "')");
		long freed = rowsTotal == 0 ? 0 : size * wouldDelete / rowsTotal;

		long affected = wouldDelete;
		if (!dryRun && wouldDelete > 0) {
			jdbc.update(cte(s.rollupInsert()));
			affected = jdbc.update(cte("delete from " + s.table() + " " + s.alias() + " where " + s.candidate()));
		}
		return new SourceReport(s.table(), s.kind(), rowsTotal, affected, keptRecent, keptAnchored, rollupDays, freed);
	}

	private long count(String sql) {
		Long v = jdbc.queryForObject(sql, Long.class);
		return v == null ? 0 : v;
	}

	/** Prepend the shared events CTE to a statement. */
	private static String cte(String body) {
		return "with " + EVENTS + " " + body;
	}

	// ----- per-source candidate predicates (numbers come from trusted config, inlined literally) -----

	private String socialCandidate(String a) {
		return a + ".posted_at < now() - interval '" + keepRawDays + " days'"
				+ " and not exists (select 1 from events e where e.ticker = " + a + ".ticker"
				+ "   and e.at between " + a + ".posted_at - interval '" + anchorDays + " days'"
				+ "   and " + a + ".posted_at + interval '" + anchorDays + " days')"
				+ " and coalesce(abs(" + a + ".sentiment_score), 0) < " + num(sentimentKeep);
	}

	private String webCandidate(String a) {
		// web_mentions carries only a sentiment label (no numeric score/relevance), so keep is by
		// recency / event-anchor only.
		return a + ".posted_at < now() - interval '" + keepRawDays + " days'"
				+ " and not exists (select 1 from events e where e.ticker = " + a + ".ticker"
				+ "   and e.at between " + a + ".posted_at - interval '" + anchorDays + " days'"
				+ "   and " + a + ".posted_at + interval '" + anchorDays + " days')";
	}

	private String newsCandidate(String a) {
		return a + ".published_at < now() - interval '" + keepRawDays + " days'"
				+ " and " + a + ".tickers is not null and array_length(" + a + ".tickers, 1) is not null"
				+ " and not exists (select 1 from events e join unnest(" + a + ".tickers) t on e.ticker = t"
				+ "   and e.at between " + a + ".published_at - interval '" + anchorDays + " days'"
				+ "   and " + a + ".published_at + interval '" + anchorDays + " days')"
				+ " and coalesce(" + a + ".relevance_score, 0) < " + num(relevanceKeep)
				+ " and coalesce(abs(" + a + ".sentiment_score), 0) < " + num(sentimentKeep)
				+ " and not exists (select 1 from news_card c where c.article_id = " + a + ".id)";
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}

	/** A firehose table plus the SQL fragments the cleanup logic needs for it. */
	private final class Source {
		private final String table;
		private final String kind;
		private final String timeCol;
		private final String alias;
		private final String candidate;

		Source(String table, String kind, String timeCol, String alias, String candidate) {
			this.table = table;
			this.kind = kind;
			this.timeCol = timeCol;
			this.alias = alias;
			this.candidate = candidate;
		}

		String table() {
			return table;
		}

		String kind() {
			return kind;
		}

		String alias() {
			return alias;
		}

		String candidate() {
			return candidate;
		}

		String withinWindow() {
			return alias + "." + timeCol + " >= now() - interval '" + keepRawDays + " days'";
		}

		String olderThanCutoff() {
			return alias + "." + timeCol + " < now() - interval '" + keepRawDays + " days'";
		}

		String anchored() {
			return "NEWS".equals(kind)
					? "exists (select 1 from events e join unnest(" + alias + ".tickers) t on e.ticker = t"
							+ " where e.at between " + alias + ".published_at - interval '" + anchorDays + " days'"
							+ " and " + alias + ".published_at + interval '" + anchorDays + " days')"
					: "exists (select 1 from events e where e.ticker = " + alias + ".ticker"
							+ " and e.at between " + alias + "." + timeCol + " - interval '" + anchorDays + " days'"
							+ " and " + alias + "." + timeCol + " + interval '" + anchorDays + " days')";
		}

		String distinctTickerDay() {
			if ("NEWS".equals(kind)) {
				return "select distinct t as ticker, " + alias + ".published_at::date as day from " + table + " "
						+ alias + ", unnest(" + alias + ".tickers) t where " + candidate;
			}
			return "select distinct " + alias + ".ticker, " + alias + "." + timeCol + "::date from " + table + " "
					+ alias + " where " + candidate;
		}

		/** INSERT ... SELECT that folds the disposable rows into sentiment_daily (accumulating). */
		String rollupInsert() {
			String head = "insert into sentiment_daily "
					+ "(kind, ticker, day, post_count, bullish_count, bearish_count, neutral_count,"
					+ " sentiment_sum, relevance_sum, first_at, last_at, updated_at) select ";
			String tail = " on conflict (kind, ticker, day) do update set"
					+ " post_count = sentiment_daily.post_count + excluded.post_count,"
					+ " bullish_count = sentiment_daily.bullish_count + excluded.bullish_count,"
					+ " bearish_count = sentiment_daily.bearish_count + excluded.bearish_count,"
					+ " neutral_count = sentiment_daily.neutral_count + excluded.neutral_count,"
					+ " sentiment_sum = sentiment_daily.sentiment_sum + excluded.sentiment_sum,"
					+ " relevance_sum = sentiment_daily.relevance_sum + excluded.relevance_sum,"
					+ " first_at = least(sentiment_daily.first_at, excluded.first_at),"
					+ " last_at = greatest(sentiment_daily.last_at, excluded.last_at),"
					+ " updated_at = now()";
			String buckets = "count(*),"
					+ " count(*) filter (where %1$s.sentiment_label = 'BULLISH'),"
					+ " count(*) filter (where %1$s.sentiment_label = 'BEARISH'),"
					+ " count(*) filter (where %1$s.sentiment_label is null"
					+ "   or %1$s.sentiment_label not in ('BULLISH','BEARISH')),";
			if ("SOCIAL".equals(kind)) {
				return head + "'SOCIAL', " + alias + ".ticker, " + alias + ".posted_at::date, "
						+ String.format(buckets, alias)
						+ " coalesce(sum(" + alias + ".sentiment_score), 0), 0,"
						+ " min(" + alias + ".posted_at), max(" + alias + ".posted_at), now()"
						+ " from " + table + " " + alias + " where " + candidate
						+ " group by " + alias + ".ticker, " + alias + ".posted_at::date" + tail;
			}
			if ("WEB".equals(kind)) {
				return head + "'WEB', " + alias + ".ticker, " + alias + ".posted_at::date, "
						+ String.format(buckets, alias)
						+ " coalesce(sum(case " + alias + ".sentiment_label when 'BULLISH' then 1"
						+ "   when 'BEARISH' then -1 else 0 end), 0), 0,"
						+ " min(" + alias + ".posted_at), max(" + alias + ".posted_at), now()"
						+ " from " + table + " " + alias + " where " + candidate
						+ " group by " + alias + ".ticker, " + alias + ".posted_at::date" + tail;
			}
			// NEWS — expand the tickers array so each concerned ticker gets the article's signal.
			return head + "'NEWS', t, " + alias + ".published_at::date, "
					+ String.format(buckets, alias)
					+ " coalesce(sum(" + alias + ".sentiment_score), 0), coalesce(sum(" + alias + ".relevance_score), 0),"
					+ " min(" + alias + ".published_at), max(" + alias + ".published_at), now()"
					+ " from " + table + " " + alias + ", unnest(" + alias + ".tickers) t where " + candidate
					+ " group by t, " + alias + ".published_at::date" + tail;
		}
	}

	private String summarize(boolean dryRun, long deleted, long kept, long rollupDays, long freed) {
		String verb = dryRun ? "Would remove" : "Removed";
		return "%s %,d disposable rows (keeping %,d), rolled up into %,d ticker-day summaries, freeing ~%s."
				.formatted(verb, deleted, kept, rollupDays, humanBytes(freed));
	}

	private void persist(CleanupReport r) {
		Map<String, Object> reportJson = new LinkedHashMap<>();
		List<Map<String, Object>> tables = new ArrayList<>();
		for (SourceReport s : r.sources()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("table", s.table());
			m.put("kind", s.kind());
			m.put("rowsTotal", s.rowsTotal());
			m.put("affected", s.affected());
			m.put("keptRecent", s.keptRecent());
			m.put("keptAnchored", s.keptAnchored());
			m.put("rollupDays", s.rollupDays());
			m.put("freedBytes", s.freedBytes());
			tables.add(m);
		}
		reportJson.put("tables", tables);
		jdbc.update(
				"insert into cleanup_run (started_at, finished_at, dry_run, deleted_rows, kept_rows,"
						+ " rolled_up_days, freed_bytes, report, summary) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)",
				java.sql.Timestamp.from(r.startedAt()), java.sql.Timestamp.from(r.finishedAt()), r.dryRun(),
				r.deletedRows(), r.keptRows(), r.rolledUpDays(), r.freedBytes(), JSON.writeValueAsString(reportJson),
				r.summary());
	}

	private static String humanBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = {"KB", "MB", "GB", "TB"};
		double v = bytes / 1024.0;
		int i = 0;
		while (v >= 1024 && i < units.length - 1) {
			v /= 1024;
			i++;
		}
		return String.format(Locale.ROOT, v < 10 ? "%.1f %s" : "%.0f %s", v, units[i]);
	}
}
