package com.argus.security;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the admin's usage-stats signal: for each (user, day), the first and last authenticated
 * request seen. Called from {@link SessionAuthFilter} on every authenticated request, so it
 * de-dupes in memory first ({@value #MIN_INTERVAL_SECONDS}s) — without that, a page polling every
 * few seconds would write a row on nearly every request.
 */
@Service
public class UserActivityService {

	static final long MIN_INTERVAL_SECONDS = 60;
	private static final Duration MIN_INTERVAL = Duration.ofSeconds(MIN_INTERVAL_SECONDS);

	private final UserActivityDayRepository days;
	private final Map<Long, Instant> lastTouch = new ConcurrentHashMap<>();

	public UserActivityService(UserActivityDayRepository days) {
		this.days = days;
	}

	/** Best-effort: never let activity tracking fail the request it's piggybacking on. */
	public void touch(Long userId) {
		Instant now = Instant.now();
		Instant prev = lastTouch.get(userId);
		if (prev != null && Duration.between(prev, now).compareTo(MIN_INTERVAL) < 0) {
			return;
		}
		lastTouch.put(userId, now);
		try {
			recordDay(userId, now);
		} catch (RuntimeException ex) {
			// swallow — activity tracking is a nice-to-have, not core to the request succeeding
		}
	}

	@Transactional
	void recordDay(Long userId, Instant now) {
		LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
		days.findById_UserIdAndId_ActivityDate(userId, today)
				.ifPresentOrElse(d -> {
					d.touch(now);
					days.save(d);
				}, () -> days.save(new UserActivityDay(userId, today, now)));
	}

	/** The saved history for one user, most recent day first. */
	@Transactional(readOnly = true)
	public List<UserActivityDay> historyFor(Long userId) {
		return days.findById_UserIdOrderById_ActivityDateDesc(userId);
	}
}
