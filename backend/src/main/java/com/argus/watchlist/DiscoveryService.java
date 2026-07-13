package com.argus.watchlist;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The auto-discovery agent (watchlist Phase 2). It looks beyond your portfolio for names the market is
 * talking about: it ranks the tickers most cash-tagged in recently-ingested social chatter that you
 * don't already hold or watch, and promotes the top {@code maxDiscovered} into the watchlist as
 * DISCOVERED entries with an expiry. Those flow through {@link CompositeKnownUniverse} exactly like
 * manual entries — the agents start covering them and Agent 5 recommends on them — but they auto-expire
 * and refresh so the outward-looking set stays current without unbounded growth.
 *
 * <p>Common ETFs, index proxies and crypto tickers are filtered out ({@link #DENYLIST}) so it surfaces
 * individual companies, not the market itself. Deterministic (no LLM), additive, and reversible.
 */
@Service
public class DiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);

	/** ETFs / index proxies / crypto / common non-company cashtags to never surface as opportunities. */
	private static final Set<String> DENYLIST = Set.of(
			"SPY", "QQQ", "IWM", "DIA", "VOO", "VTI", "VT", "VXX", "UVXY", "GLD", "SLV", "USO", "TLT", "HYG",
			"ARKK", "SOXL", "SOXS", "TQQQ", "SQQQ", "SPXL", "SPXS", "UPRO", "TSLL",
			"BTC", "ETH", "DOGE", "XRP", "SOL", "USDT", "USD", "EUR",
			"CPI", "GDP", "FED", "ETF", "IPO", "USA", "US", "AI", "CEO", "FOMC");

	private final JdbcTemplate jdbc;
	private final WatchlistRepository watchlist;
	private final DiscoveryProperties props;

	public DiscoveryService(JdbcTemplate jdbc, WatchlistRepository watchlist, DiscoveryProperties props) {
		this.jdbc = jdbc;
		this.watchlist = watchlist;
		this.props = props;
	}

	/** Twice-daily scan; additive and idempotent, so failures never cascade. */
	@Scheduled(cron = "${argus.discovery.cron:0 15 */12 * * *}")
	public void scheduledDiscover() {
		try {
			discover();
		}
		catch (RuntimeException ex) {
			log.warn("Auto-discovery failed: {}", ex.getMessage());
		}
	}

	/** Prune expired discoveries, then promote the current top trending non-portfolio tickers. */
	@Transactional
	public int discover() {
		if (!props.enabled()) {
			return 0;
		}
		// Drop expired discoveries so the outward set stays fresh (manual entries are never touched).
		jdbc.update("delete from watchlist where source = 'DISCOVERED' and expires_at is not null and expires_at < now()");

		Instant expiresAt = Instant.now().plus(props.ttlDays(), ChronoUnit.DAYS);
		int promoted = 0;
		for (Candidate c : rankCandidates()) {
			if (DENYLIST.contains(c.ticker()) || c.mentions() < props.minMentions()) {
				continue;
			}
			var existing = watchlist.findByTicker(c.ticker());
			if (existing.isPresent()) {
				// Refresh a still-trending discovery's expiry; never override a manual pick.
				if ("DISCOVERED".equals(existing.get().getSource())) {
					jdbc.update("update watchlist set expires_at = ?, note = ?, active = true where ticker = ?",
							java.sql.Timestamp.from(expiresAt), note(c), c.ticker());
				}
				continue;
			}
			if (activeDiscoveredCount() >= props.maxDiscovered()) {
				break; // capped — don't flood the universe (and the ingestion rate limits)
			}
			watchlist.save(new WatchlistEntry(c.ticker(), WatchlistEntry.Source.DISCOVERED, note(c), expiresAt));
			promoted++;
		}
		if (promoted > 0) {
			log.info("Auto-discovery promoted {} trending ticker(s) to the watchlist", promoted);
		}
		return promoted;
	}

	private long activeDiscoveredCount() {
		Long n = jdbc.queryForObject(
				"select count(*) from watchlist where source = 'DISCOVERED' and active "
						+ "and (expires_at is null or expires_at > now())",
				Long.class);
		return n == null ? 0 : n;
	}

	/** Cash-tag frequency over the social firehose, minus anything already in the known universe. */
	private List<Candidate> rankCandidates() {
		String sql = """
				with universe as (
				  select ticker from positions where ticker is not null
				  union select ticker from watchlist where active
				),
				tags as (
				  select upper(m[1]) as ticker, count(*) as c
				  from social_posts sp, regexp_matches(sp.body, '\\$([A-Za-z]{1,5})', 'g') as m
				  where sp.posted_at > now() - (? || ' days')::interval
				  group by upper(m[1])
				)
				select ticker, c from tags
				where ticker not in (select ticker from universe)
				order by c desc
				limit 40
				""";
		return jdbc.query(sql, (rs, i) -> new Candidate(rs.getString("ticker"), rs.getInt("c")),
				String.valueOf(props.lookbackDays()));
	}

	private static String note(Candidate c) {
		return "Trending: " + c.mentions() + " social mentions";
	}

	private record Candidate(String ticker, int mentions) {
	}
}
