package com.argus.cost;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Proactive budget alerts (Story 10.6, FR-46). On a schedule it checks the {@link CostGovernor} band
 * and, when spend crosses into a higher band, notifies the user — 70% NOTICE as a briefing-tier note,
 * 80% WARNING as an immediate push (auto-switch to local follows if budget is hit), 95% CRITICAL as a
 * require-ack push (cloud calls paused). It alerts only on escalation (not every tick), and the band
 * naturally resets when the monthly counter rolls over.
 */
@Component
public class BudgetWatcher {

	private static final Logger log = LoggerFactory.getLogger(BudgetWatcher.class);

	private final CostGovernor governor;
	private final NotificationService notifications;
	private volatile String lastBand = "NORMAL";

	public BudgetWatcher(CostGovernor governor, NotificationService notifications) {
		this.governor = governor;
		this.notifications = notifications;
	}

	@Scheduled(fixedDelayString = "${argus.budget.watch-ms:1800000}",
			initialDelayString = "${argus.budget.watch-ms:1800000}")
	public void check() {
		try {
			BudgetStatus status = governor.status();
			if (escalated(lastBand, status.band())) {
				notifyBand(status);
			}
			lastBand = status.band();
		} catch (RuntimeException ex) {
			log.debug("Budget watch failed: {}", ex.getMessage());
		}
	}

	private void notifyBand(BudgetStatus s) {
		int pct = (int) Math.round(s.percentUsed());
		switch (s.band()) {
			case "NOTICE" -> notifications.notify(Notification.of(UrgencyTier.NORMAL,
					"Budget at " + pct + "%",
					"Paid-API spend reached " + pct + "% of this month's budget.", "/agents"));
			case "WARNING" -> notifications.notify(Notification.of(UrgencyTier.IMPORTANT,
					"Budget at " + pct + "%",
					"Spend is high — escalations will auto-switch to the local model if the budget is reached.", "/agents"));
			case "CRITICAL" -> notifications.notify(Notification.of(UrgencyTier.CRITICAL,
					"Budget at " + pct + "% — cloud paused",
					"Monthly budget reached. All paid calls are paused (100% local) until next cycle.", "/agents"));
			default -> { /* NORMAL — nothing to announce */ }
		}
	}

	/** True when {@code next} is a strictly higher band than {@code prev} (alert only on escalation). */
	static boolean escalated(String prev, String next) {
		return rank(next) > rank(prev);
	}

	private static int rank(String band) {
		return switch (band) {
			case "NOTICE" -> 1;
			case "WARNING" -> 2;
			case "CRITICAL" -> 3;
			default -> 0;
		};
	}
}
