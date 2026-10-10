package com.argus.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Server-side session store backed by Redis (Decision 5 — revocable; enables FR-39 remote kill).
 * Each session is a Redis <b>hash</b> at {@code argus:session:{id}} holding metadata (created, last
 * seen, device label); the id is an opaque 256-bit token (the cookie value). The idle TTL is the
 * user-configurable timeout from {@link SettingsService} (Story 2.3): finite values are set + slid
 * on each {@link #validate}; "Never" stores no expiry.
 *
 * <p>For listing/revocation (Story 2.7) sessions are exposed by a non-reversible <b>handle</b>
 * (truncated SHA-256 of the id), so raw session tokens never reach the browser/JS.
 */
@Component
public class SessionStore {

	static final String KEY_PREFIX = "argus:session:";
	private static final Duration NEVER_COOKIE_MAX_AGE = Duration.ofDays(400);
	private static final String F_CREATED = "created";
	private static final String F_SEEN = "seen";
	private static final String F_DEVICE = "device";
	private static final String F_USER_ID = "userId";

	// Atomic check-and-touch: only refresh last-seen / slide TTL if the session still EXISTS, so a
	// session that idle-expired or was remote-killed between checks is never resurrected by HSET
	// (which would otherwise create a partial, possibly TTL-less, zombie). Returns 1 if alive.
	private static final RedisScript<Long> TOUCH = RedisScript.of(
			"if redis.call('EXISTS', KEYS[1]) == 1 then "
					+ "redis.call('HSET', KEYS[1], 'seen', ARGV[1]); "
					+ "if tonumber(ARGV[2]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end; "
					+ "return 1 else return 0 end",
			Long.class);

	private final StringRedisTemplate redis;
	private final SettingsService settings;
	private final SecureRandom random = new SecureRandom();
	private final Base64.Encoder b64url = Base64.getUrlEncoder().withoutPadding();

	public SessionStore(StringRedisTemplate redis, SettingsService settings) {
		this.redis = redis;
		this.settings = settings;
	}

	/** Create a session for the given device label; returns its opaque id. No user attached (legacy
	 * PIN path — {@link #userId(String)} returns empty for a session created this way). */
	public String create(String device) {
		return create(device, null);
	}

	/** Create a session for the given device, signed in as {@code userId} (Google login). */
	public String create(String device, Long userId) {
		String id = newId();
		String now = Instant.now().toString();
		Map<String, String> fields = new java.util.HashMap<>(Map.of(
				F_CREATED, now, F_SEEN, now, F_DEVICE, device == null || device.isBlank() ? "Unknown device" : device));
		if (userId != null) {
			fields.put(F_USER_ID, userId.toString());
		}
		redis.opsForHash().putAll(key(id), fields);
		settings.sessionTimeout().ifPresent(ttl -> redis.expire(key(id), ttl));
		return id;
	}

	/** Convenience for callers/tests without device context. */
	public String create() {
		return create("Unknown device");
	}

	/** The signed-in user's id for a live session, or empty if the session is gone, invalid, or
	 * predates user accounts (the old PIN login created sessions with no user attached). */
	public Optional<Long> userId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		Object v = redis.opsForHash().get(key(id), F_USER_ID);
		return v == null ? Optional.empty() : Optional.of(Long.parseLong(v.toString()));
	}

	/**
	 * True if the session exists <b>and</b> has a signed-in {@code userId} (S-A6 / M2); atomically
	 * refreshes last-seen and (for finite timeouts) slides the TTL. Never resurrects a missing key
	 * (see {@link #TOUCH}). Legacy PIN-era sessions without {@code userId} fail — they must not
	 * authenticate API requests or report {@code authenticated=true} with {@code user=null}.
	 */
	public boolean validate(String id) {
		if (id == null || id.isBlank()) {
			return false;
		}
		long ttlMillis = settings.sessionTimeout().map(Duration::toMillis).orElse(0L);
		Long alive = redis.execute(TOUCH, List.of(key(id)), Instant.now().toString(), Long.toString(ttlMillis));
		if (alive == null || alive != 1L) {
			return false;
		}
		return redis.opsForHash().get(key(id), F_USER_ID) != null;
	}

	/** Destroy a session (logout / remote kill). Idempotent. */
	public void destroy(String id) {
		if (id != null && !id.isBlank()) {
			redis.delete(key(id));
		}
	}

	/**
	 * List active sessions <b>owned by {@code ownerUserId}</b>, marking which one matches
	 * {@code currentId} (Story 2.7 / S-A2). Never returns another person's sessions.
	 */
	public List<SessionInfo> list(String currentId, Long ownerUserId) {
		List<SessionInfo> out = new ArrayList<>();
		if (ownerUserId == null) {
			return out;
		}
		Set<String> keys = redis.keys(KEY_PREFIX + "*"); // friend-group scale — a handful of sessions
		if (keys == null) {
			return out;
		}
		String owner = ownerUserId.toString();
		String currentHandle = currentId == null ? null : handle(currentId);
		for (String k : keys) {
			Map<Object, Object> h = redis.opsForHash().entries(k);
			if (h.isEmpty()) {
				continue;
			}
			Object uid = h.get(F_USER_ID);
			if (uid == null || !owner.equals(uid.toString())) {
				continue;
			}
			String id = k.substring(KEY_PREFIX.length());
			String hdl = handle(id);
			out.add(new SessionInfo(
					hdl,
					str(h.get(F_DEVICE), "Unknown device"),
					str(h.get(F_CREATED), null),
					str(h.get(F_SEEN), null),
					hdl.equals(currentHandle)));
		}
		return out;
	}

	/** End every session signed in as {@code userId} (admin revoked or deleted them). Returns how many. */
	public int revokeAllForUser(Long userId) {
		Set<String> keys = redis.keys(KEY_PREFIX + "*");
		if (keys == null || userId == null) {
			return 0;
		}
		int removed = 0;
		for (String k : keys) {
			Object owner = redis.opsForHash().get(k, F_USER_ID);
			if (owner != null && userId.toString().equals(owner.toString())) {
				redis.delete(k);
				removed++;
			}
		}
		return removed;
	}

	/**
	 * Revoke the session whose handle matches <b>and</b> belongs to {@code ownerUserId} (Story 2.7 /
	 * S-A2). Returns true if one was removed; false if missing or owned by someone else (caller should
	 * treat both as not-found — no cross-user oracle).
	 */
	public boolean revokeByHandle(String targetHandle, Long ownerUserId) {
		Set<String> keys = redis.keys(KEY_PREFIX + "*");
		if (keys == null || targetHandle == null || ownerUserId == null) {
			return false;
		}
		String owner = ownerUserId.toString();
		for (String k : keys) {
			String id = k.substring(KEY_PREFIX.length());
			if (!handle(id).equals(targetHandle)) {
				continue;
			}
			Object uid = redis.opsForHash().get(k, F_USER_ID);
			if (uid == null || !owner.equals(uid.toString())) {
				return false;
			}
			redis.delete(k);
			return true;
		}
		return false;
	}

	/**
	 * Cookie Max-Age to pair with a new session. Empty = a session cookie (no Max-Age): for finite
	 * timeouts the server TTL is authoritative; "Never" returns a long Max-Age so the session
	 * persists across restarts.
	 */
	public Optional<Duration> cookieMaxAge() {
		return settings.sessionTimeout().isEmpty() ? Optional.of(NEVER_COOKIE_MAX_AGE) : Optional.empty();
	}

	private String newId() {
		byte[] bytes = new byte[32]; // 256 bits
		random.nextBytes(bytes);
		return b64url.encodeToString(bytes);
	}

	/** Non-reversible public handle for a session id (truncated SHA-256), safe to expose to JS. */
	private String handle(String id) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
			return b64url.encodeToString(digest).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String str(Object value, String fallback) {
		return value == null ? fallback : value.toString();
	}

	private String key(String id) {
		return KEY_PREFIX + id;
	}

	/** Public view of an active session (no raw token). {@code current} = the caller's own session. */
	public record SessionInfo(String handle, String device, String createdAt, String lastActiveAt, boolean current) {
	}
}
