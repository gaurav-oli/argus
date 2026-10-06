package com.argus.ops;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells someone when data goes stale instead of waiting to be noticed. The freshness rollup only lit a
 * header on the Agents page, so a source could sit stale for days (fundamentals did) unless somebody
 * happened to open it. Every 30 minutes this checks {@link FreshnessService} and sends one IMPORTANT
 * alert when a source turns stale — not again while it stays stale — and a note when it recovers.
 */
@Component
public class FreshnessWatcher {

	private static final Logger log = LoggerFactory.getLogger(FreshnessWatcher.class);

	private final FreshnessService freshness;
	private final NotificationService notifications;
	/** Sources already alerted as stale, so a long outage is one alert, not one every half hour. */
	private final Set<String> alerted = ConcurrentHashMap.newKeySet();

	/** Off in tests so no alert is pushed from a test JVM. */
	@Value("${argus.freshness.alerts-enabled:true}")
	private boolean enabled = true;

	public FreshnessWatcher(FreshnessService freshness, NotificationService notifications) {
		this.freshness = freshness;
		this.notifications = notifications;
	}

	@Scheduled(fixedDelay = 1_800_000, initialDelay = 900_000)
	public void check() {
		if (!enabled) {
			return;
		}
		try {
			for (SourceFreshness s : freshness.snapshot().sources()) {
				if (s.stale() && alerted.add(s.source())) {
					notify(UrgencyTier.IMPORTANT, s.label() + " is stale",
							(s.lastUpdateAt() == null ? "It has never updated" : "Last updated " + humanAge(s.ageMinutes()) + " ago")
									+ " — expected within " + humanAge(s.thresholdMinutes()) + ". Anything built on it may be out of date.");
				}
				else if (!s.stale() && alerted.remove(s.source())) {
					notify(UrgencyTier.INFO, s.label() + " is fresh again", "It updated " + humanAge(s.ageMinutes()) + " ago.");
				}
			}
		}
		catch (RuntimeException ex) {
			log.warn("Freshness check failed: {}", ex.getMessage());
		}
	}

	private void notify(UrgencyTier tier, String title, String body) {
		try {
			notifications.notify(Notification.of(tier, title, body, "/agents"));
		}
		catch (RuntimeException ex) {
			log.warn("Freshness alert '{}' failed: {}", title, ex.getMessage());
		}
	}

	static String humanAge(Long minutes) {
		if (minutes == null) {
			return "unknown";
		}
		if (minutes < 120) {
			return minutes + " min";
		}
		long hours = minutes / 60;
		return hours < 48 ? hours + "h" : (hours / 24) + " days";
	}
}
