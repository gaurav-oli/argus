package com.argus.social;

import com.argus.intelligence.SentimentLabel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * StockTwits crowd source for Agent 2. Uses the public symbol-stream endpoint (no key required;
 * an optional {@code argus.stocktwits.access-token} raises rate limits). Each message carries the
 * poster's own Bullish/Bearish tag when they set one, which we use directly as the crowd sentiment.
 */
@Component
public class StockTwitsSource implements SocialSource {

	static final String NAME = "stocktwits";
	private static final Logger log = LoggerFactory.getLogger(StockTwitsSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String accessToken;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public StockTwitsSource(@Value("${argus.stocktwits.access-token:}") String accessToken) {
		this.accessToken = accessToken;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawSocialPost> fetch(Collection<String> heldTickers) {
		List<RawSocialPost> out = new ArrayList<>();
		for (String ticker : heldTickers) {
			try {
				out.addAll(fetchTicker(ticker));
			}
			catch (RuntimeException | InterruptedException ex) {
				log.debug("StockTwits fetch failed for {}: {}", ticker, ex.getMessage());
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return out;
	}

	private List<RawSocialPost> fetchTicker(String ticker) throws InterruptedException {
		String url = "https://api.stocktwits.com/api/2/streams/symbol/" + ticker + ".json"
				+ (accessToken.isBlank() ? "" : "?access_token=" + accessToken);
		HttpResponse<String> res;
		try {
			res = http.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "Argus/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		if (res.statusCode() != 200) {
			log.debug("StockTwits {} → HTTP {}", ticker, res.statusCode());
			return List.of();
		}
		List<RawSocialPost> posts = new ArrayList<>();
		for (JsonNode m : JSON.readTree(res.body()).path("messages")) {
			String id = m.path("id").asString();
			String body = m.path("body").asString();
			if (id == null || id.isBlank() || body == null || body.isBlank()) {
				continue;
			}
			String author = m.path("user").path("username").asString();
			Instant postedAt = parseInstant(m.path("created_at").asString());
			SentimentLabel hint = mapSentiment(m.path("entities").path("sentiment").path("basic").asString());
			posts.add(new RawSocialPost(NAME, id, ticker, author, body,
					"https://stocktwits.com/message/" + id, postedAt, hint));
		}
		return posts;
	}

	private static SentimentLabel mapSentiment(String basic) {
		if (basic == null) {
			return null;
		}
		return switch (basic) {
			case "Bullish" -> SentimentLabel.BULLISH;
			case "Bearish" -> SentimentLabel.BEARISH;
			default -> null;
		};
	}

	private static Instant parseInstant(String value) {
		try {
			return value == null ? Instant.now() : Instant.parse(value);
		}
		catch (RuntimeException ex) {
			return Instant.now();
		}
	}
}
