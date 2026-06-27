package com.argus.notification;

/**
 * A candidate notification handed to {@link NotificationService} (Epic 8). The discipline pipeline
 * (dedup → fatigue gate → tier routing) decides whether and how it reaches the user.
 *
 * @param tier            urgency tier (Story 8.2) — drives routing and whether the gate is bypassed
 * @param ticker          the symbol this concerns, or {@code null} for non-ticker alerts (skips dedup)
 * @param direction       e.g. BULLISH/BEARISH/STRANGER — paired with {@code ticker} for dedup (Story 8.4)
 * @param confidence      0–1 confidence, used by the fatigue gate and dedup (Story 8.3/8.4)
 * @param portfolioImpact 0–1 fraction of the portfolio affected, used by the fatigue gate (Story 8.3)
 * @param title           push heading
 * @param body            push message
 * @param url             in-app path to open on click
 */
public record Notification(
		UrgencyTier tier,
		String ticker,
		String direction,
		double confidence,
		double portfolioImpact,
		String title,
		String body,
		String url) {

	/** A non-ticker alert (skips dedup; gate uses full confidence/impact so only the tier matters). */
	public static Notification of(UrgencyTier tier, String title, String body, String url) {
		return new Notification(tier, null, null, 1.0, 1.0, title, body, url);
	}

	/** A ticker alert carrying the confidence + portfolio impact the gate and dedup need. */
	public static Notification forTicker(UrgencyTier tier, String ticker, String direction,
			double confidence, double portfolioImpact, String title, String body, String url) {
		return new Notification(tier, ticker, direction, confidence, portfolioImpact, title, body, url);
	}
}
