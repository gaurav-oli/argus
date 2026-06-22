package com.argus.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Server-side session store backed by Redis (Decision 5 — sessions, not JWTs, so they can be
 * revoked instantly; enables FR-39 remote kill in Story 2.7). A session is an opaque 256-bit
 * random id; the value is a small marker string. Each session carries a TTL equal to
 * {@link SecurityProperties#sessionTtl()}; {@link #validate} slides it forward on activity so an
 * active user isn't logged out mid-use.
 */
@Component
public class SessionStore {

	static final String KEY_PREFIX = "argus:session:";

	private final StringRedisTemplate redis;
	private final Duration ttl;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder idEncoder = Base64.getUrlEncoder().withoutPadding();

	public SessionStore(StringRedisTemplate redis, SecurityProperties properties) {
		this.redis = redis;
		this.ttl = properties.sessionTtl();
	}

	/** Create a new session and return its opaque id. */
	public String create() {
		String id = newId();
		redis.opsForValue().set(key(id), "1", ttl);
		return id;
	}

	/** True if the session exists; refreshes (slides) its TTL when it does. */
	public boolean validate(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}
		Boolean refreshed = redis.expire(key(id), ttl);
		return Boolean.TRUE.equals(refreshed);
	}

	/** Destroy a session (logout / remote kill). Idempotent. */
	public void destroy(String id) {
		if (id != null && !id.isBlank()) {
			redis.delete(key(id));
		}
	}

	/** Configured session TTL — used to align the cookie Max-Age. */
	public Duration ttl() {
		return ttl;
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
