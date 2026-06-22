package com.argus.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Server-side session store backed by Redis (Decision 5 — revocable, enables FR-39). A session is
 * an opaque 256-bit id; the value is a small marker. The idle TTL is the user-configurable timeout
 * from {@link SettingsService} (Story 2.3): finite values are set + slid on each {@link #validate};
 * "Never" (empty) stores no expiry. The server TTL is authoritative for idle-lock — see
 * {@link #cookieMaxAge()} for how the cookie defers to it.
 */
@Component
public class SessionStore {

	static final String KEY_PREFIX = "argus:session:";
	/** "Never" → a long-lived cookie so the session survives restarts (no idle expiry server-side). */
	private static final Duration NEVER_COOKIE_MAX_AGE = Duration.ofDays(400);

	private final StringRedisTemplate redis;
	private final SettingsService settings;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder idEncoder = Base64.getUrlEncoder().withoutPadding();

	public SessionStore(StringRedisTemplate redis, SettingsService settings) {
		this.redis = redis;
		this.settings = settings;
	}

	/** Create a new session and return its opaque id. */
	public String create() {
		String id = newId();
		Optional<Duration> ttl = settings.sessionTimeout();
		if (ttl.isPresent()) {
			redis.opsForValue().set(key(id), "1", ttl.get());
		} else {
			redis.opsForValue().set(key(id), "1"); // Never — no expiry
		}
		return id;
	}

	/** True if the session exists; for finite timeouts also slides the idle TTL. */
	public boolean validate(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}
		Optional<Duration> ttl = settings.sessionTimeout();
		if (ttl.isPresent()) {
			return Boolean.TRUE.equals(redis.expire(key(id), ttl.get()));
		}
		return Boolean.TRUE.equals(redis.hasKey(key(id)));
	}

	/** Destroy a session (logout / remote kill). Idempotent. */
	public void destroy(String id) {
		if (id != null && !id.isBlank()) {
			redis.delete(key(id));
		}
	}

	/**
	 * Cookie Max-Age to pair with a new session. Empty = a session cookie (no Max-Age): for finite
	 * timeouts the server TTL is authoritative, so the cookie can't expire mid-activity. "Never"
	 * returns a long Max-Age so the session persists across restarts.
	 */
	public Optional<Duration> cookieMaxAge() {
		return settings.sessionTimeout().isEmpty() ? Optional.of(NEVER_COOKIE_MAX_AGE) : Optional.empty();
	}

	private String newId() {
		byte[] bytes = new byte[32]; // 256 bits
		random.nextBytes(bytes);
		return idEncoder.encodeToString(bytes);
	}

	private String key(String id) {
		return KEY_PREFIX + id;
	}
}
