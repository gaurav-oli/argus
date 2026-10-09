package com.argus.intelligence;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * GDELT Doc 2.0 source for Agent 1 (Story 4.1, FR-8) — a free, keyless broad finance feed. Returns
 * headlines only (no summary); relevance to holdings is resolved downstream by the tagger. Active by
 * default; disable with {@code argus.news.gdelt.enabled=false}. Failures yield an empty list so a
 * GDELT outage never breaks the cycle.
 *
 * <p>GDELT's free tier rate-limits aggressively; hitting it on every ingestion cycle regardless of a
 * recent failure just earns more 429s / stalled connections. On a failure this backs off (doubling,
 * capped) and skips the call entirely until the backoff clears — still polled from the same schedule,
 * just not hammered while it's clearly throttling this source.
 */
@Component
@ConditionalOnProperty(name = "argus.news.gdelt.enabled", havingValue = "true", matchIfMissing = true)
public class GdeltNewsSource implements NewsSource {

	static final String NAME = "gdelt";

	private static final Logger log = LoggerFactory.getLogger(GdeltNewsSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	// GDELT seendate: e.g. 20260623T120000Z.
	private static final DateTimeFormatter SEENDATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final Duration INITIAL_BACKOFF = Duration.ofMinutes(10);
	private static final Duration MAX_BACKOFF = Duration.ofHours(2);

	private final String query;
	private final int maxRecords;
	// GDELT's TLS handshake routinely takes ~10s (measured 9.5s on 2026-10-08). The old 5s connect timeout failed
	// every single fetch from 2026-06-27 on — three months with no world news from GDELT, unnoticed.
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
	private volatile Instant backoffUntil = Instant.EPOCH;

	public GdeltNewsSource(NewsIngestionProperties props) {
		this.query = props.gdelt().query();
		this.maxRecords = props.gdelt().maxRecords();
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawArticle> fetch(Collection<String> heldTickers) {
		Instant now = Instant.now();
		if (now.isBefore(backoffUntil)) {
			log.debug("GDELT: backing off ({} consecutive failure(s)) — skipping until {}", consecutiveFailures.get(), backoffUntil);
			return List.of();
		}
		try {
			String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
			URI uri = URI.create("https://api.gdeltproject.org/api/v2/doc/doc?query=" + q
					+ "&mode=ArtList&format=json&sort=DateDesc&maxrecords=" + maxRecords);
			HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				log.warn("GDELT returned HTTP {}", res.statusCode());
				recordFailure(now);
				return List.of();
			}
			JsonNode articles = JSON.readTree(res.body()).path("articles");
			List<RawArticle> out = new ArrayList<>();
			for (JsonNode n : articles) {
				String url = n.path("url").asString("").trim();
				String headline = n.path("title").asString("").trim();
				Instant published = parseSeendate(n.path("seendate").asString(""));
				if (url.isEmpty() || headline.isEmpty() || published == null) {
					continue; // drop undated items rather than stamping them "now" (would skew the window)
				}
				out.add(new RawArticle(NAME, url, url, headline, null, published, List.of()));
			}
			consecutiveFailures.set(0);
			backoffUntil = Instant.EPOCH;
			return out;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return List.of();
		} catch (RuntimeException | java.io.IOException ex) {
			log.warn("GDELT fetch failed: {}", ex.getMessage());
			recordFailure(now);
			return List.of();
		}
	}

	private void recordFailure(Instant now) {
		int failures = consecutiveFailures.incrementAndGet();
		backoffUntil = now.plus(Backoff.duration(failures, INITIAL_BACKOFF, MAX_BACKOFF));
		log.info("GDELT: {} consecutive failure(s) — backing off until {}", failures, backoffUntil);
	}

	/** Parse GDELT's seendate, or {@code null} if it's missing/malformed (caller drops the item). */
	private static Instant parseSeendate(String raw) {
		try {
			return LocalDateTime.parse(raw, SEENDATE).toInstant(ZoneOffset.UTC);
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
