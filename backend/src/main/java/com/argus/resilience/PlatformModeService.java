package com.argus.resilience;

import com.argus.common.LivePushService;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Degraded Mode coordinator (Story 10.4 / GAP-3). {@link ConnectivityProbe} reports reachability;
 * this service debounces it into a {@link PlatformMode}, announces transitions (a CRITICAL push on
 * going offline, an IMPORTANT push with a catch-up note on reconnect), and broadcasts the mode to
 * {@code /topic/platform-mode} for the dashboard banner. Other components consult {@link #isDegraded()}
 * to pause net-dependent work or add stale-data warnings.
 */
@Service
public class PlatformModeService {

	static final String TOPIC = "/topic/platform-mode";
	/** Consecutive failed probes before declaring DEGRADED — one blip shouldn't flip the mode. */
	static final int FAILURES_TO_DEGRADE = 2;

	private static final Logger log = LoggerFactory.getLogger(PlatformModeService.class);

	private final NotificationService notifications;
	private final LivePushService livePush;

	private volatile PlatformMode mode = PlatformMode.NORMAL;
	private volatile Instant since = Instant.now();
	private volatile String reason = "online";
	private Instant degradedSince;
	private int consecutiveFailures;

	public PlatformModeService(NotificationService notifications, LivePushService livePush) {
		this.notifications = notifications;
		this.livePush = livePush;
	}

	public boolean isDegraded() {
		return mode == PlatformMode.DEGRADED;
	}

	public PlatformModeView current() {
		return new PlatformModeView(mode.name(), since, reason);
	}

	/**
	 * Feed one connectivity observation; applies debounce and drives transitions. Returns the resulting
	 * view. {@code now} is passed in so the transition logic is unit-testable.
	 */
	public synchronized PlatformModeView report(boolean online, Instant now) {
		if (online) {
			consecutiveFailures = 0;
			if (mode == PlatformMode.DEGRADED) {
				Duration outage = degradedSince == null ? Duration.ZERO : Duration.between(degradedSince, now);
				degradedSince = null;
				transition(PlatformMode.NORMAL, now, "back online");
				announce(UrgencyTier.IMPORTANT, "Back online",
						"Reconnected after " + humanize(outage) + " — catching up on data missed while offline.");
			}
		} else {
			consecutiveFailures++;
			if (mode == PlatformMode.NORMAL && consecutiveFailures >= FAILURES_TO_DEGRADE) {
				degradedSince = now;
				transition(PlatformMode.DEGRADED, now, "internet unreachable");
				announce(UrgencyTier.CRITICAL, "⚠️ Degraded mode",
						"Internet appears down — live feeds paused, showing last-known values. Local analysis still works.");
			}
		}
		return current();
	}

	private void transition(PlatformMode next, Instant now, String why) {
		log.warn("Platform mode {} -> {} ({})", mode, next, why);
		mode = next;
		since = now;
		reason = why;
		try {
			livePush.publish(TOPIC, current());
		} catch (RuntimeException ex) {
			log.debug("platform-mode broadcast failed: {}", ex.getMessage());
		}
	}

	private void announce(UrgencyTier tier, String title, String body) {
		try {
			notifications.notify(Notification.of(tier, title, body, "/"));
		} catch (RuntimeException ex) {
			log.debug("platform-mode notification failed: {}", ex.getMessage());
		}
	}

	private static String humanize(Duration d) {
		long minutes = Math.max(1, d.toMinutes());
		if (minutes < 60) {
			return minutes + "m";
		}
		long hours = minutes / 60;
		long rem = minutes % 60;
		return rem == 0 ? hours + "h" : hours + "h " + rem + "m";
	}
}
