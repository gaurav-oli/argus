package com.argus.intelligence;

import com.argus.cost.CostGovernor;
import com.argus.model.ModelGateway;
import com.argus.notification.NotificationPreferencesService;
import com.argus.notification.NotificationPreferencesService.Category;
import com.argus.push.PushService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Detects immediate, market-moving news and pushes it to the user's phone. Evaluated as each article
 * finishes sentiment analysis (Agent 1). A story alerts when it's either strongly material to the
 * holdings (relevance × |sentiment| ≥ {@code impactThreshold}) or it hits a market-wide breaking topic
 * ({@link #BREAKING_TOPICS} — war, the Fed, tariffs, a market-moving political remark, a crash, …) with
 * enough sentiment behind it. Alerts are deduped by headline and rate-limited so the phone gets the big
 * events without being spammed. The push reuses the existing Web-Push channel ({@link PushService}).
 */
@Service
public class BreakingNewsAlertService {

	private static final Logger log = LoggerFactory.getLogger(BreakingNewsAlertService.class);

	/** Lower-cased substrings that flag a market-wide breaking event regardless of holdings-relevance. */
	private static final Set<String> BREAKING_TOPICS = Set.of(
			"war", "invasion", "invade", "missile", "airstrike", "air strike", "attack", "strike on",
			"nuclear", "ceasefire", "sanction", "tariff", "embargo", "opec", "oil price",
			"trump", "white house", "federal reserve", "fed ", "rate cut", "rate hike", "interest rate",
			"inflation", "recession", "jobs report", "cpi", "downgrade", "default", "shutdown", "bankrupt",
			"crash", "plunge", "selloff", "sell-off", "circuit breaker", "emergency", "bailout", "coup");

	private final BreakingAlertRepository alerts;
	private final PushService push;
	private final ModelGateway gateway;
	private final CostGovernor costGovernor;
	private final NotificationPreferencesService prefs;
	private final BreakingNewsProperties props;

	public BreakingNewsAlertService(BreakingAlertRepository alerts, PushService push, ModelGateway gateway,
			CostGovernor costGovernor, NotificationPreferencesService prefs, BreakingNewsProperties props) {
		this.alerts = alerts;
		this.push = push;
		this.gateway = gateway;
		this.costGovernor = costGovernor;
		this.prefs = prefs;
		this.props = props;
	}

	/** Evaluate a freshly-analyzed article; push + record it if it clears the bar and the guards. */
	public void evaluate(NewsArticle article) {
		if (!props.enabled() || article == null || article.getSentimentLabel() == null) {
			return;
		}
		double sentiment = article.getSentimentScore() == null ? 0 : Math.abs(article.getSentimentScore().doubleValue());
		double relevance = article.getRelevanceScore() == null ? 0 : article.getRelevanceScore().doubleValue();
		double impact = relevance * sentiment;
		String headline = article.getHeadline();
		if (headline == null || headline.isBlank()) {
			return;
		}
		// "Immediate" means fresh — never push an old story a feed happened to re-list.
		if (article.getPublishedAt() == null
				|| article.getPublishedAt().isBefore(Instant.now().minus(Duration.ofHours(props.maxAgeHours())))) {
			return;
		}

		String topic = matchedTopic(headline);
		boolean strongForHoldings = impact >= props.impactThreshold();
		boolean breakingMacro = topic != null && sentiment >= props.sentimentMin();
		if (!strongForHoldings && !breakingMacro) {
			return;
		}

		Instant now = Instant.now();
		if (alerts.existsByHeadlineAndCreatedAtAfter(headline, now.minus(Duration.ofMinutes(props.cooldownMinutes())))) {
			return; // same story already pushed recently
		}
		if (alerts.countByCreatedAtAfter(now.minus(Duration.ofHours(1))) >= props.maxPerHour()) {
			log.info("Breaking-news rate limit hit — suppressing push for: {}", headline);
			return;
		}

		// Precision gate: a fast Haiku YES/NO to drop plausible-but-not-really-market-moving stories.
		// Fail-open (send on any error) and skipped when the budget has paused paid calls, so alerts
		// are never lost to a flaky/absent model.
		if (props.llmConfirm() && costGovernor.allowPaidCall() && !confirmMarketMoving(headline)) {
			log.info("Breaking-news LLM gate suppressed (not market-moving): {}", headline);
			return;
		}

		String reason = strongForHoldings
				? "High impact for your holdings"
				: "Breaking: " + topic;
		// Always record it (the in-app Breaking feed keeps the full history); the push is what prefs gate.
		alerts.save(new BreakingAlert(headline, article.getUrl(), article.getTickers(), reason, impact,
				article.getSentimentLabel().name()));

		if (!prefs.allow(Category.BREAKING, article.getTickers(), false)) {
			log.info("Breaking-news recorded but push suppressed by preferences (off/muted/quiet): {}", headline);
			return;
		}
		int delivered = push.sendToAll("⚠️ Market alert", headline, "/intelligence", true);
		log.info("Breaking-news alert pushed to {} device(s) [{}]: {}", delivered, reason, headline);
	}

	/**
	 * Ask Haiku whether a headline is genuinely market-moving enough for an immediate phone alert.
	 * Returns true (send) on any error or ambiguous answer — the deterministic filter already qualified
	 * it, so the LLM only ever removes clear false positives, never adds risk of a missed real alert.
	 */
	private boolean confirmMarketMoving(String headline) {
		String prompt = """
				You are a strict market-alert filter. Answer with ONLY one word: YES or NO.
				Would this news headline plausibly move US stock markets or a major stock within hours, \
				such that an investor would want an immediate phone alert? Say NO for routine analysis, \
				opinion pieces, price-target chatter, listicles, and old news.

				HEADLINE: %s
				""".formatted(headline);
		try {
			String answer = gateway.escalate(prompt);
			if (answer == null) {
				return true;
			}
			String a = answer.strip().toUpperCase(Locale.ROOT);
			return !a.startsWith("NO"); // explicit NO suppresses; anything else sends
		}
		catch (RuntimeException ex) {
			log.warn("Breaking-news LLM confirm failed ({}) — sending on the deterministic decision", ex.getMessage());
			return true; // fail-open
		}
	}

	private static String matchedTopic(String headline) {
		String h = headline.toLowerCase();
		for (String topic : BREAKING_TOPICS) {
			if (h.contains(topic)) {
				return topic.trim();
			}
		}
		return null;
	}
}
