package com.argus.backup;

import com.argus.backup.BackupStatusService.BackupStatusView;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches the backup status (Story 10.1's alerting half) and raises edge-triggered pushes through
 * the normal notification discipline: 🔴 CRITICAL once when the backup destination disappears
 * (disconnected drive / broken mount), 🟡 IMPORTANT once when the newest full dump goes stale
 * (launchd job failing silently). Recovery resets the edge so a repeat failure alerts again.
 */
@Component
public class BackupWatcher {

	private static final Logger log = LoggerFactory.getLogger(BackupWatcher.class);

	private final BackupStatusService status;
	private final NotificationService notifications;

	private volatile boolean destinationWasConnected = true;
	private volatile boolean fullWasFresh = true;

	public BackupWatcher(BackupStatusService status, NotificationService notifications) {
		this.status = status;
		this.notifications = notifications;
	}

	@Scheduled(fixedDelayString = "${argus.backup.watch-ms:600000}",
			initialDelayString = "${argus.backup.watch-initial-delay-ms:120000}")
	public void watch() {
		BackupStatusView v = status.status();
		if (!v.enabled()) {
			return;
		}
		if (!v.destinationConnected()) {
			if (destinationWasConnected) {
				notify(UrgencyTier.CRITICAL, "🔴 Backup drive disconnected",
						"The backup destination isn't reachable — backups are paused until it's back.");
			}
			destinationWasConnected = false;
			return; // staleness is meaningless while the destination is gone
		}
		destinationWasConnected = true;

		boolean fullFresh = v.full() != null && !v.full().stale();
		if (!fullFresh && fullWasFresh) {
			notify(UrgencyTier.IMPORTANT, "🟡 Backups are stale",
					"No fresh full backup found — check the launchd job (launchctl list | grep com.argus.backup).");
		}
		fullWasFresh = fullFresh;
	}

	private void notify(UrgencyTier tier, String title, String body) {
		try {
			notifications.notify(Notification.of(tier, title, body, "/agents"));
		} catch (RuntimeException ex) {
			log.debug("backup alert failed: {}", ex.getMessage());
		}
	}
}
