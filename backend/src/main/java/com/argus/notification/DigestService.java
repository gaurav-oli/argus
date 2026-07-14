package com.argus.notification;

import com.argus.notification.DeferredNotification.Channel;
import com.argus.push.PushService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weekly digest INFO-tier alerts are deferred to (Story 8.2 follow-up — the tier routing
 * promised it; this delivers it). Sundays at 17:00 Toronto ({@code argus.digest.cron}, {@code -}
 * disables) it summarizes the week's undelivered DIGEST items into one push — gated by the same
 * briefing preference toggle, since the digest is briefing-class, not an alert — and marks them
 * delivered. No items → no push (an empty digest is noise).
 */
@Service
public class DigestService {

	private static final Logger log = LoggerFactory.getLogger(DigestService.class);
	private static final int MAX_TITLES_IN_PUSH = 3;
	private static final Duration WINDOW = Duration.ofDays(7);

	private final DeferredNotificationRepository deferred;
	private final PushService push;
	private final NotificationPreferencesService prefs;

	public DigestService(DeferredNotificationRepository deferred, PushService push,
			NotificationPreferencesService prefs) {
		this.deferred = deferred;
		this.push = push;
		this.prefs = prefs;
	}

	@Scheduled(cron = "${argus.digest.cron:0 0 17 * * SUN}", zone = "America/Toronto")
	public void weeklyDigest() {
		try {
			send();
		} catch (RuntimeException ex) {
			log.warn("Weekly digest failed: {}", ex.getMessage());
		}
	}

	/** One digest pass: summarize + push + mark delivered. Returns how many items it carried. */
	@Transactional
	public int send() {
		List<DeferredNotification> items = deferred
				.findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
						Channel.DIGEST, Instant.now().minus(WINDOW));
		if (items.isEmpty()) {
			log.debug("Weekly digest: nothing deferred this week — skipping");
			return 0;
		}
		if (prefs.allow(NotificationPreferencesService.Category.BRIEFING)) {
			push.sendToAll("Your weekly digest", body(items), "/intelligence");
		}
		items.forEach(DeferredNotification::markDelivered);
		deferred.saveAll(items);
		log.info("Weekly digest delivered {} item(s)", items.size());
		return items.size();
	}

	private static String body(List<DeferredNotification> items) {
		String titles = items.stream().limit(MAX_TITLES_IN_PUSH)
				.map(DeferredNotification::getTitle)
				.reduce((a, b) -> a + " · " + b).orElse("");
		int more = items.size() - MAX_TITLES_IN_PUSH;
		return items.size() + " low-priority item(s) this week: " + titles
				+ (more > 0 ? " (+" + more + " more)" : "");
	}
}
