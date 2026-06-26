package com.argus.social;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reddit crowd source for Agent 2 — searches investing subreddits for held tickers. Key-gated
 * (dormant until {@code argus.reddit.client-id} is set): uses app-only OAuth (client credentials).
 * Untested live (no keys yet); best-effort, returns empty on any failure so it never breaks a cycle.
 */
@Component
@ConditionalOnExpression("'${argus.reddit.client-id:}'.length() > 0")
public class RedditSource implements SocialSource {

	static final String NAME = "reddit";
	private static final String SUBS = "wallstreetbets+stocks+investing+StockMarket";
	private static final Logger log = LoggerFactory.getLogger(RedditSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String clientId;
	private final String clientSecret;
	private final String userAgent;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public RedditSource(@Value("${argus.reddit.client-id:}") String clientId,
			@Value("${argus.reddit.client-secret:}") String clientSecret,
			@Value("${argus.reddit.user-agent:Argus/1.0}") String userAgent) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.userAgent = userAgent;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawSocialPost> fetch(Collection<String> heldTickers) {
		String token;
		try {
			token = accessToken();
		}
		catch (RuntimeException | InterruptedException ex) {
			log.debug("Reddit auth failed: {}", ex.getMessage());
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return List.of();
		}
		List<RawSocialPost> out = new ArrayList<>();
		for (String ticker : heldTickers) {
			try {
				out.addAll(search(ticker, token));
			}
			catch (RuntimeException | InterruptedException ex) {
				log.debug("Reddit search failed for {}: {}", ticker, ex.getMessage());
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return out;
	}

	private String accessToken() throws InterruptedException {
		String basic = Base64.getEncoder()
				.encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
		HttpResponse<String> res = send(HttpRequest.newBuilder()
				.uri(URI.create("https://www.reddit.com/api/v1/access_token"))
				.header("Authorization", "Basic " + basic).header("User-Agent", userAgent)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build());
		return JSON.readTree(res.body()).path("access_token").asString();
	}

	private List<RawSocialPost> search(String ticker, String token) throws InterruptedException {
		String url = "https://oauth.reddit.com/r/" + SUBS + "/search?q=" + ticker
				+ "&restrict_sr=1&sort=new&limit=15&t=day";
		HttpResponse<String> res = send(HttpRequest.newBuilder().uri(URI.create(url))
				.header("Authorization", "bearer " + token).header("User-Agent", userAgent).GET().build());
		List<RawSocialPost> posts = new ArrayList<>();
		for (JsonNode child : JSON.readTree(res.body()).path("data").path("children")) {
			JsonNode d = child.path("data");
			String id = d.path("id").asString();
			String title = d.path("title").asString("");
			String selftext = d.path("selftext").asString("");
			String body = (title + " " + selftext).strip();
			if (id == null || id.isBlank() || body.isBlank()) {
				continue;
			}
			Instant postedAt = d.has("created_utc")
					? Instant.ofEpochSecond(d.path("created_utc").asLong()) : Instant.now();
			posts.add(new RawSocialPost(NAME, id, ticker, d.path("author").asString(), body,
					"https://reddit.com" + d.path("permalink").asString(""), postedAt, null));
		}
		return posts;
	}

	private HttpResponse<String> send(HttpRequest req) throws InterruptedException {
		try {
			return http.send(req, HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
