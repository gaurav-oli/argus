package com.argus.intelligence;

import com.argus.marketdata.FinnhubRest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finnhub company-news source for Agent 1 (Story 4.1, FR-8). One REST call per held ticker against
 * {@code /company-news}, scoped to a short lookback window. Only active when {@code argus.finnhub.api-key}
 * is set (free dev key on the laptop, or the Mini); with no key the bean is absent and ingestion
 * falls back to the free sources. All calls go through {@link FinnhubRest}, which rate-limits and
 * retries them (Story 4.5, GAP-4), so an approached rate limit degrades gracefully to GDELT/RSS.
 */
@Component
@ConditionalOnProperty(name = "argus.finnhub.api-key")
public class FinnhubNewsSource implements NewsSource {

	static final String NAME = "finnhub";

	private static final Logger log = LoggerFactory.getLogger(FinnhubNewsSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String apiKey;
	private final long lookbackMinutes;
	private final FinnhubRest finnhub;

	public FinnhubNewsSource(@Value("${argus.finnhub.api-key}") String apiKey,
			NewsIngestionProperties props, FinnhubRest finnhub) {
		this.apiKey = apiKey;
		this.lookbackMinutes = props.lookbackMinutes();
		this.finnhub = finnhub;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawArticle> fetch(Collection<String> heldTickers) {
		if (heldTickers == null || heldTickers.isEmpty()) {
			return List.of();
		}
		Instant cutoff = Instant.now().minus(Duration.ofMinutes(lookbackMinutes));
		// Finnhub company-news takes whole-day from/to; the per-article cutoff below trims within.
		LocalDate from = cutoff.atZone(ZoneOffset.UTC).toLocalDate();
		LocalDate to = LocalDate.now(ZoneOffset.UTC);
		List<RawArticle> out = new ArrayList<>();
		for (String ticker : heldTickers) {
			out.addAll(fetchTicker(ticker, from, to, cutoff));
		}
		return out;
	}

	private List<RawArticle> fetchTicker(String ticker, LocalDate from, LocalDate to, Instant cutoff) {
		String url = "https://finnhub.io/api/v1/company-news?symbol=" + ticker
				+ "&from=" + from + "&to=" + to + "&token=" + apiKey;
		Optional<String> body = finnhub.get(url); // rate-limited + retried; empty when dropped (Story 4.5)
		if (body.isEmpty()) {
			return List.of();
		}
		try {
			JsonNode arr = JSON.readTree(body.get());
			List<RawArticle> items = new ArrayList<>();
			for (JsonNode n : arr) {
				Instant published = Instant.ofEpochSecond(n.path("datetime").asLong());
				if (published.isBefore(cutoff)) {
					continue;
				}
				String headline = n.path("headline").asString("").trim();
				if (headline.isEmpty()) {
					continue;
				}
				items.add(new RawArticle(NAME, String.valueOf(n.path("id").asLong()),
						emptyToNull(n.path("url").asString("")), headline,
						emptyToNull(n.path("summary").asString("")), published, List.of(ticker)));
			}
			return items;
		} catch (RuntimeException ex) {
			log.warn("Finnhub company-news {} parse failed: {}", ticker, ex.getMessage());
			return List.of();
		}
	}

	private static String emptyToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
