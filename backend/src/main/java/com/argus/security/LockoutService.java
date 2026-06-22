package com.argus.security;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Escalating failed-attempt lockout for PIN login (FR-38 / Story 2.6), backed by Redis so it
 * survives restarts and is shared across devices. The counter climbs across lockout windows and
 * resets only on a successful login (or an explicit clear from another device). Lockout is applied
 * to the PIN path only — biometric unlock (a possession+inherence factor) is not brute-forceable
 * and stays available to the legitimate user.
 *
 * <ul>
 *   <li>{@code warnThreshold} fails → short timed lockout</li>
 *   <li>{@code alertThreshold} fails → longer timed lockout + secondary-device alert (Epic 8)</li>
 *   <li>{@code fullLockThreshold} fails → full lock, clearable only via {@link #clear()} from an
 *       already-authenticated device</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(LockoutProperties.class)
public class LockoutService {

	private static final Logger log = LoggerFactory.getLogger(LockoutService.class);

	static final String KEY_FAILS = "argus:auth:fails";
	static final String KEY_LOCK = "argus:auth:lock";
	private static final String LOCK_TIMED = "timed";
	private static final String LOCK_FULL = "full";

	private final StringRedisTemplate redis;
	private final LockoutProperties props;

	public LockoutService(StringRedisTemplate redis, LockoutProperties props) {
		this.redis = redis;
		this.props = props;
	}

	/** Reject the attempt before any PIN check if a lockout is currently in effect. */
	public void assertNotLocked() {
		String lock = redis.opsForValue().get(KEY_LOCK);
		if (lock == null) {
			return;
		}
		if (LOCK_FULL.equals(lock)) {
			throw LockedException.full();
		}
		Long ttl = redis.getExpire(KEY_LOCK, TimeUnit.SECONDS);
		throw LockedException.timed(ttl == null ? 1 : ttl);
	}

	/**
	 * Record a failed attempt and, if a threshold is crossed, arm the matching lockout. Throws the
	 * resulting {@link LockedException} so the failing response already reflects the new lock;
	 * otherwise returns normally (the caller then throws its 401).
	 */
	public void recordFailure() {
		long fails = increment();
		if (fails >= props.fullLockThreshold()) {
			redis.opsForValue().set(KEY_LOCK, LOCK_FULL); // no expiry — needs another device
			log.warn("Auth fully locked after {} failed attempts — clear from another device", fails);
			throw LockedException.full();
		}
		if (fails >= props.alertThreshold()) {
			arm(props.alertLockout());
			// TODO(Epic 8 — Web Push): notify the secondary device on the 10-minute lockout (FR-38).
			log.warn("Auth locked {}m after {} failed attempts (secondary-device alert pending Epic 8)",
					props.alertLockout().toMinutes(), fails);
			throw LockedException.timed(props.alertLockout().toSeconds());
		}
		if (fails >= props.warnThreshold()) {
			arm(props.warnLockout());
			throw LockedException.timed(props.warnLockout().toSeconds());
		}
	}

	/** Successful login — clear the failure counter and any lockout. */
	public void reset() {
		redis.delete(java.util.List.of(KEY_FAILS, KEY_LOCK));
	}

	/** Clear a lockout from another authenticated device (FR-38 full-lock recovery). */
	public void clear() {
		reset();
	}

	/** Current lockout state for status reporting (no side effects). */
	public Lockout current() {
		String lock = redis.opsForValue().get(KEY_LOCK);
		if (lock == null) {
			return new Lockout(false, 0);
		}
		if (LOCK_FULL.equals(lock)) {
			return new Lockout(true, 0);
		}
		Long ttl = redis.getExpire(KEY_LOCK, TimeUnit.SECONDS);
		return new Lockout(false, ttl == null ? 0 : ttl);
	}

	private long increment() {
		Long fails = redis.opsForValue().increment(KEY_FAILS);
		return fails == null ? 0 : fails;
	}

	private void arm(Duration duration) {
		redis.opsForValue().set(KEY_LOCK, LOCK_TIMED, duration);
	}

	/** Lockout snapshot. {@code full} = needs another device; else {@code secondsRemaining} > 0 if timed. */
	public record Lockout(boolean full, long secondsRemaining) {
	}
}
