package com.argus.internet;

import com.argus.social.SocialSentiment;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hacker News source for Agent 3 — searches the free Algolia HN API (no key) by company name for
 * recent stories, capturing tech-community attention (points) and a keyword-classified sentiment.
 * Searches by name (not ticker) and ranks by relevance, since short tickers match too loosely.
 */
@Component
public class HackerNewsSource implements InternetSource {

	static final String NAME = "hackernews";
	private static final Logger log = LoggerFactory.getLogger(HackerNewsSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final Duration LOOKBACK = Duration.ofDays(30);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawWebMention> fetch(Collection<HeldCompany> held) {
		long cutoff = Instant.now().minus(LOOKBACK).getEpochSecond();
		List<RawWebMention> out = new ArrayList<>();
		for (HeldCompany c : held) {
			if (c.name() == null || c.name().isBlank()) {
				continue; // name-based search only
			}
			try {
				out.addAll(search(c, cutoff));
			}
			catch (RuntimeException | InterruptedException ex) {
				log.debug("HN search failed for {}: {}", c.ticker(), ex.getMessage());
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return out;
	}

	private List<RawWebMention> search(HeldCompany c, long cutoff) throws InterruptedException {
		String url = "https://hn.algolia.com/api/v1/search?tags=story&hitsPerPage=20"
				+ "&query=" + URLEncoder.encode(c.name(), StandardCharsets.UTF_8)
				+ "&numericFilters=" + URLEncoder.encode("created_at_i>" + cutoff, StandardCharsets.UTF_8);
		HttpResponse<String> res;
		try {
			res = http.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "Argus/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		if (res.statusCode() != 200) {
			return List.of();
		}
		List<RawWebMention> mentions = new ArrayList<>();
		for (JsonNode h : JSON.readTree(res.body()).path("hits")) {
			String id = h.path("objectID").asString();
			String title = h.path("title").asString();
			if (id == null || id.isBlank() || title == null || title.isBlank()) {
				continue;
			}
			long points = h.path("points").asLong();
			String storyUrl = h.path("url").isNull() || h.path("url").asString().isBlank()
					? "https://news.ycombinator.com/item?id=" + id : h.path("url").asString();
			Instant postedAt = Instant.ofEpochSecond(h.path("created_at_i").asLong());
			mentions.add(new RawWebMention(c.ticker(), NAME, "hn:" + id, title, storyUrl, points,
					SocialSentiment.classify(title), postedAt));
		}
		return mentions;
	}
}
