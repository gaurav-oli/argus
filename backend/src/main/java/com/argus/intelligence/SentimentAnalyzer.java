package com.argus.intelligence;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scores an article's sentiment and holdings-relevance via the small model (Story 4.2, FR-8). Prompts
 * for strict JSON and parses it defensively — anything unparseable (including the dev mock's canned
 * reply, or a model/timeout failure) degrades to {@link SentimentAnalysis#neutral()} rather than
 * throwing, so one bad article never stalls the pipeline. Runs on {@link ModelTier#SMALL}: unserialized
 * and with no paid fallback, since Agent 1 calls this at volume.
 */
@Component
public class SentimentAnalyzer {

	private static final Logger log = LoggerFactory.getLogger(SentimentAnalyzer.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final ModelGateway gateway;

	public SentimentAnalyzer(ModelGateway gateway) {
		this.gateway = gateway;
	}

	public SentimentAnalysis analyze(String headline, String summary, List<String> tickers) {
		try {
			String raw = gateway.generate(buildPrompt(headline, summary, tickers), ModelTier.SMALL);
			return parse(raw);
		} catch (RuntimeException ex) {
			log.warn("Sentiment analysis failed; defaulting to neutral: {}", ex.getMessage());
			return SentimentAnalysis.neutral();
		}
	}

	private static String buildPrompt(String headline, String summary, List<String> tickers) {
		String holdings = tickers.isEmpty() ? "(none identified)" : String.join(", ", tickers);
		return """
				You are a financial news analyst. Assess the article below for an investor holding: %s.
				Respond with ONLY a JSON object, no prose, in exactly this shape:
				{"sentiment":"BULLISH|BEARISH|NEUTRAL","score":<number -1..1>,"relevance":<number 0..1>}
				- score: negative = bearish, positive = bullish, magnitude = strength.
				- relevance: how material this is to the listed holdings (0 = irrelevant, 1 = highly material).

				HEADLINE: %s
				SUMMARY: %s
				""".formatted(holdings, nullToEmpty(headline), nullToEmpty(summary));
	}

	private SentimentAnalysis parse(String raw) {
		String json = extractJsonObject(raw);
		if (json == null) {
			log.debug("No JSON object in model reply; neutral");
			return SentimentAnalysis.neutral();
		}
		try {
			JsonNode node = JSON.readTree(json);
			SentimentLabel label = parseLabel(node.path("sentiment").asString(""));
			double score = clamp(node.path("score").asDouble(0.0), -1.0, 1.0);
			double relevance = clamp(node.path("relevance").asDouble(0.0), 0.0, 1.0);
			return new SentimentAnalysis(label, score, relevance);
		} catch (RuntimeException ex) {
			log.debug("Unparseable sentiment JSON; neutral: {}", ex.getMessage());
			return SentimentAnalysis.neutral();
		}
	}

	private static SentimentLabel parseLabel(String s) {
		try {
			return SentimentLabel.valueOf(s.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ex) {
			return SentimentLabel.NEUTRAL;
		}
	}

	/** Pull the first {@code {...}} block out of the reply, tolerating surrounding prose/markdown. */
	private static String extractJsonObject(String raw) {
		if (raw == null) {
			return null;
		}
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		return (start >= 0 && end > start) ? raw.substring(start, end + 1) : null;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
