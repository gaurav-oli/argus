package com.argus.internet;

import com.argus.intelligence.SentimentLabel;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 3 read endpoints — per-ticker web buzz + a recent Hacker News feed. Session-gated. */
@RestController
@RequestMapping("/api/internet")
public class InternetController {

	/** Web attention for one ticker over the recent window. */
	public record TickerBuzz(String ticker, long hnStories, long hnBullish, long hnBearish,
			long wikiViewsRecent, double attentionRatio, String mood) {
	}

	/** One Hacker News story for the feed. */
	public record StoryView(String ticker, String title, String url, long points, String sentiment,
			Instant postedAt) {
	}

	private static final int WINDOW_DAYS = 14;

	private final WebMentionRepository mentions;

	public InternetController(WebMentionRepository mentions) {
		this.mentions = mentions;
	}

	@GetMapping("/buzz")
	public List<TickerBuzz> buzz() {
		Instant now = Instant.now();
		Instant since = now.minus(WINDOW_DAYS, ChronoUnit.DAYS);
		Instant recentCut = now.minus(3, ChronoUnit.DAYS);
		Map<String, List<WebMention>> byTicker = mentions.findByPostedAtAfter(since).stream()
				.collect(Collectors.groupingBy(WebMention::getTicker));

		List<TickerBuzz> out = new ArrayList<>();
		byTicker.forEach((ticker, items) -> {
			long hn = items.stream().filter(m -> "hackernews".equals(m.getSource())).count();
			long bull = items.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BULLISH).count();
			long bear = items.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BEARISH).count();

			List<WebMention> wiki = items.stream().filter(m -> "wikipedia".equals(m.getSource())).toList();
			double recentAvg = avgViews(wiki, m -> m.getPostedAt().isAfter(recentCut));
			double baseAvg = avgViews(wiki, m -> !m.getPostedAt().isAfter(recentCut));
			double ratio = baseAvg > 0 ? recentAvg / baseAvg : (recentAvg > 0 ? 1.0 : 0.0);
			long latestViews = wiki.stream().max(Comparator.comparing(WebMention::getPostedAt))
					.map(WebMention::getScore).orElse(0L);

			out.add(new TickerBuzz(ticker, hn, bull, bear, latestViews, round(ratio),
					mood(bull, bear, ratio)));
		});
		out.sort(Comparator.comparingDouble(TickerBuzz::attentionRatio).reversed());
		return out;
	}

	@GetMapping("/feed")
	public List<StoryView> feed() {
		return mentions.findTop30BySourceOrderByPostedAtDesc("hackernews").stream()
				.map(m -> new StoryView(m.getTicker(), m.getTitle(), m.getUrl(), m.getScore(),
						m.getSentimentLabel() == null ? "NEUTRAL" : m.getSentimentLabel().name(), m.getPostedAt()))
				.toList();
	}

	private static double avgViews(List<WebMention> wiki, java.util.function.Predicate<WebMention> when) {
		return wiki.stream().filter(when).mapToLong(WebMention::getScore).average().orElse(0);
	}

	private static String mood(long bull, long bear, double ratio) {
		if (bull > bear * 1.2) {
			return "Bullish";
		}
		if (bear > bull * 1.2) {
			return "Bearish";
		}
		return ratio >= 1.3 ? "Trending" : "Quiet";
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
