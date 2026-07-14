package com.argus.notification;

import com.argus.notification.DeferredNotification.Channel;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link DeferredNotification} rows (briefing/digest queues). */
public interface DeferredNotificationRepository extends JpaRepository<DeferredNotification, Long> {

	/** Undelivered items for one channel, newest first (the briefing caps how many it folds in). */
	List<DeferredNotification> findByChannelAndDeliveredAtIsNullOrderByCreatedAtDesc(Channel channel);

	/** Undelivered items for one channel created after {@code since} (the digest's 7-day window). */
	List<DeferredNotification> findByChannelAndDeliveredAtIsNullAndCreatedAtAfterOrderByCreatedAtDesc(
			Channel channel, Instant since);
}
