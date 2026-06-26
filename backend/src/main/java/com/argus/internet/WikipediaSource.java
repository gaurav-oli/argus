package com.argus.internet;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wikipedia source for Agent 3 — daily pageviews are a clean, universal "public attention" signal
 * (free Wikimedia REST API, no key). Resolves each holding's article from its company name (cached),
 * then emits one mention per day carrying that day's view count. A spike over baseline = an
 * attention event, which Agent 5 reads as added conviction.
 */
@Component
public class WikipediaSource implements InternetSource {

	static final String NAME = "wikipedia";
	private static final Logger log = LoggerFactory.getLogger(WikipediaSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final String UA = "Argus/1.0 (argus@example.com)";
	private static final int LOOKBACK_DAYS = 14;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Map<String, String> articleByTicker = new ConcurrentHashMap<>();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawWebMention> fetch(Collection<HeldCompany> held) {
		List<RawWebMention> out = new ArrayList<>();
		LocalDate end = LocalDate.now(ZoneOffset.UTC).minusDays(1); // Wikimedia data lags ~1 day
		LocalDate start = end.minusDays(LOOKBACK_DAYS);
		for (HeldCompany c : held) {
			try {
				String article = resolveArticle(c);
				if (article != null) {
					out.addAll(pageviews(c.ticker(), article, start, end));
				}
			}
			catch (RuntimeException | InterruptedException ex) {
				log.debug("Wikipedia fetch failed for {}: {}", c.ticker(), ex.getMessage());
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return out;
	}

	private String resolveArticle(HeldCompany c) throws InterruptedException {
		String cached = articleByTicker.get(c.ticker());
		if (cached != null) {
			return cached.isBlank() ? null : cached;
		}
		String query = c.name() != null && !c.name().isBlank() ? c.name() : c.ticker();
		String url = "https://en.wikipedia.org/w/rest.php/v1/search/title?limit=1&q="
				+ URLEncoder.encode(query, StandardCharsets.UTF_8);
		JsonNode pages = JSON.readTree(get(url)).path("pages");
		String key = pages.isEmpty() ? "" : pages.get(0).path("key").asString("");
		articleByTicker.put(c.ticker(), key); // cache hit or miss (stable per holding)
		return key.isBlank() ? null : key;
	}

	private List<RawWebMention> pageviews(String ticker, String article, LocalDate start, LocalDate end)
			throws InterruptedException {
		String encoded = URLEncoder.encode(article, StandardCharsets.UTF_8).replace("+", "%20");
		String url = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/"
				+ "all-access/all-agents/" + encoded + "/daily/" + start.format(DAY) + "/" + end.format(DAY);
		List<RawWebMention> out = new ArrayList<>();
		String articleUrl = "https://en.wikipedia.org/wiki/" + article;
		for (JsonNode item : JSON.readTree(get(url)).path("items")) {
			String stamp = item.path("timestamp").asString(); // yyyymmdd00
			if (stamp == null || stamp.length() < 8) {
				continue;
			}
			String day = stamp.substring(0, 8);
			long views = item.path("views").asLong();
			out.add(new RawWebMention(ticker, NAME, "wiki:" + ticker + ":" + day,
					article.replace('_', ' ') + " — Wikipedia views", articleUrl, views, null,
					LocalDate.parse(day, DAY).atStartOfDay(ZoneOffset.UTC).toInstant()));
		}
		return out;
	}

	private String get(String url) throws InterruptedException {
		try {
			HttpResponse<String> res = http.send(HttpRequest.newBuilder().uri(URI.create(url))
					.timeout(Duration.ofSeconds(10)).header("User-Agent", UA).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				throw new IllegalStateException("HTTP " + res.statusCode());
			}
			return res.body();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
