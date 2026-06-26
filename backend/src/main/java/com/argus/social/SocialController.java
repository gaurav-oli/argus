package com.argus.social;

import com.argus.intelligence.SentimentLabel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 2 read endpoints — per-ticker crowd sentiment + a recent feed. Session-gated like all /api. */
@RestController
@RequestMapping("/api/social")
public class SocialController {

	/** Crowd sentiment for one ticker over the recent window. */
	public record TickerSentiment(String ticker, long bullish, long bearish, long neutral, long total,
			String mood) {
	}

	/** One social post for the feed. */
	public record SocialPostView(String ticker, String source, String author, String body, String url,
			String sentiment, Instant postedAt) {
	}

	private static final int WINDOW_DAYS = 7;

	private final SocialPostRepository posts;

	public SocialController(SocialPostRepository posts) {
		this.posts = posts;
	}

	@GetMapping("/sentiment")
	public List<TickerSentiment> sentiment() {
		Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
		Map<String, long[]> byTicker = new LinkedHashMap<>();
		for (Object[] row : posts.sentimentCountsSince(since)) {
			String ticker = (String) row[0];
			SentimentLabel label = (SentimentLabel) row[1];
			long count = ((Number) row[2]).longValue();
			long[] c = byTicker.computeIfAbsent(ticker, k -> new long[3]);
			switch (label) {
				case BULLISH -> c[0] += count;
				case BEARISH -> c[1] += count;
				case NEUTRAL -> c[2] += count;
			}
		}
		List<TickerSentiment> out = new ArrayList<>();
		byTicker.forEach((ticker, c) -> {
			long total = c[0] + c[1] + c[2];
			out.add(new TickerSentiment(ticker, c[0], c[1], c[2], total, mood(c[0], c[1])));
		});
		out.sort(Comparator.comparingLong(TickerSentiment::total).reversed());
		return out;
	}

	@GetMapping("/feed")
	public List<SocialPostView> feed() {
		return posts.findTop50ByOrderByPostedAtDesc().stream()
				.map(p -> new SocialPostView(p.getTicker(), p.getSource(), p.getAuthor(), p.getBody(),
						p.getUrl(), p.getSentimentLabel() == null ? "NEUTRAL" : p.getSentimentLabel().name(),
						p.getPostedAt()))
				.toList();
	}

	private static String mood(long bull, long bear) {
		if (bull > bear * 1.2) {
			return "Bullish";
		}
		if (bear > bull * 1.2) {
			return "Bearish";
		}
		return "Mixed";
	}
}
