package com.argus.notification;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link NotificationDedupStore} (Story 8.4). The window maximum and the collapsed-count
 * live under per-key strings with a TTL equal to the dedup window, so state self-expires — no schema.
 */
@Component
public class RedisNotificationDedupStore implements NotificationDedupStore {

	private static final String MAX_PREFIX = "argus:notif:dedup:";
	private static final String COUNT_PREFIX = "argus:notif:dedupcount:";

	private final StringRedisTemplate redis;

	public RedisNotificationDedupStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean accept(String ticker, String direction, double confidence, Duration window) {
		if (ticker == null || ticker.isBlank()) {
			return true; // dedup is per ticker+direction; non-ticker alerts always pass
		}
		String maxKey = MAX_PREFIX + key(ticker, direction);
		String existing = redis.opsForValue().get(maxKey);
		if (existing != null && confidence <= parse(existing)) {
			String countKey = COUNT_PREFIX + key(ticker, direction);
			redis.opsForValue().increment(countKey);
			redis.expire(countKey, window);
			return false;
		}
		redis.opsForValue().set(maxKey, Double.toString(confidence), window);
		return true;
	}

	@Override
	public long dedupeCount(String ticker, String direction) {
		if (ticker == null || ticker.isBlank()) {
			return 0;
		}
		String value = redis.opsForValue().get(COUNT_PREFIX + key(ticker, direction));
		return value == null ? 0 : Long.parseLong(value);
	}

	private static String key(String ticker, String direction) {
		return ticker.trim().toUpperCase() + ":" + (direction == null ? "" : direction.trim().toUpperCase());
	}

	private static double parse(String s) {
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
