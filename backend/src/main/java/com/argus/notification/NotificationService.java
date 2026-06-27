package com.argus.notification;

import com.argus.push.PushService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single entry point producers call to alert the user (Epic 8) — it applies the alert-discipline
 * pipeline so only signal reaches the device:
 *
 * <ol>
 *   <li><b>Dedup</b> (Story 8.4): collapse the same ticker+direction within the window to the highest.</li>
 *   <li><b>Fatigue gate</b> (Story 8.3): non-critical alerts fire only above confidence + impact thresholds.</li>
 *   <li><b>Tier routing</b> (Story 8.2): CRITICAL/IMPORTANT push now; NORMAL → briefing; INFO → digest.</li>
 * </ol>
 *
 * Suppressed and deduped alerts are logged (per the acceptance criteria). The actual Web Push fan-out
 * is delegated to {@link PushService}.
 */
@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final NotificationProperties props;
	private final NotificationDedupStore dedup;
	private final PushService push;

	public NotificationService(NotificationProperties props, NotificationDedupStore dedup, PushService push) {
		this.props = props;
		this.dedup = dedup;
		this.push = push;
	}

	/** Run a candidate notification through dedup → gate → tier routing. Returns what happened. */
	public NotificationOutcome notify(Notification n) {
		Duration window = Duration.ofSeconds(props.dedupWindowSeconds());

		// 8.4 — dedup (applies to all tiers; identical criticals shouldn't ping repeatedly either)
		if (!dedup.accept(n.ticker(), n.direction(), n.confidence(), window)) {
			log.info("Notification deduped: {}/{} conf {} (collapsed {} in window)",
					n.ticker(), n.direction(), n.confidence(), dedup.dedupeCount(n.ticker(), n.direction()));
			return NotificationOutcome.SUPPRESSED_DEDUP;
		}

		// 8.3 — fatigue gate (CRITICAL bypasses)
		if (n.tier() != UrgencyTier.CRITICAL && !passesGate(n)) {
			log.info("Notification gated (below thresholds): '{}' conf {} impact {} (min {}/{})",
					n.title(), n.confidence(), n.portfolioImpact(), props.minConfidence(), props.minPortfolioImpact());
			return NotificationOutcome.SUPPRESSED_GATE;
		}

		// 8.2 — tier routing
		return switch (n.tier()) {
			case CRITICAL -> {
				push.sendToAll(n.title(), n.body(), n.url(), true); // require ack
				yield NotificationOutcome.PUSHED;
			}
			case IMPORTANT -> {
				push.sendToAll(n.title(), n.body(), n.url(), false);
				yield NotificationOutcome.PUSHED;
			}
			case NORMAL -> {
				log.debug("Notification deferred to next briefing: '{}'", n.title());
				yield NotificationOutcome.DEFERRED_BRIEFING;
			}
			case INFO -> {
				log.debug("Notification deferred to weekly digest: '{}'", n.title());
				yield NotificationOutcome.DEFERRED_DIGEST;
			}
		};
	}

	private boolean passesGate(Notification n) {
		return n.confidence() >= props.minConfidence() && n.portfolioImpact() >= props.minPortfolioImpact();
	}
}
