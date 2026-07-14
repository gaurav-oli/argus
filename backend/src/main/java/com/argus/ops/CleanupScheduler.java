package com.argus.ops;

import com.argus.notification.NotificationPreferencesService;
import com.argus.push.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled wrapper for the Smart Cleanup agent (Fable 5 review item 7 — it was built on-demand
 * only). Monthly by default ({@code argus.cleanup.cron}, 04:00 on the 1st; set {@code -} to go back
 * to manual-only), it runs the same audited roll-up-then-delete pass as the Agents-page button and
 * pushes the run's summary through the notification-preferences gate, so the user hears what was
 * reclaimed without having to remember to press Preview. The report stays in {@code cleanup_run}
 * either way.
 */
@Component
public class CleanupScheduler {

	private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

	private final CleanupService cleanup;
	private final PushService push;
	private final NotificationPreferencesService prefs;

	public CleanupScheduler(CleanupService cleanup, PushService push, NotificationPreferencesService prefs) {
		this.cleanup = cleanup;
		this.push = push;
		this.prefs = prefs;
	}

	@Scheduled(cron = "${argus.cleanup.cron:0 0 4 1 * *}", zone = "America/Toronto")
	public void monthlyCleanup() {
		try {
			CleanupService.CleanupReport report = cleanup.run();
			if (prefs.allow(NotificationPreferencesService.Category.ALERT)) {
				push.sendToAll("Monthly data cleanup", report.summary(), "/agents");
			}
		}
		catch (RuntimeException ex) {
			log.warn("Scheduled cleanup failed: {}", ex.getMessage());
		}
	}
}
