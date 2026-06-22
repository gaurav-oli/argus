package com.argus.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App settings (Story 2.3) — currently the configurable session timeout. The value is cached in
 * memory (single-process modular monolith) so the per-request session check never hits the DB;
 * the cache is loaded at startup and refreshed on write.
 *
 * <p>{@code sessionTimeout()} empty = "Never" (no idle expiry). No stored row → the
 * {@code argus.security.session-ttl} default.
 */
@Service
public class SettingsService {

	private final AppSettingsRepository repo;
	private final StringRedisTemplate redis;
	private final Duration defaultTimeout;
	private volatile Optional<Duration> sessionTimeout = Optional.empty();

	public SettingsService(AppSettingsRepository repo, StringRedisTemplate redis, SecurityProperties properties) {
		this.repo = repo;
		this.redis = redis;
		this.defaultTimeout = properties.sessionTtl();
	}

	@PostConstruct
	void load() {
		this.sessionTimeout = readFromDb();
	}

	/** The effective idle timeout. Empty Optional = Never (no expiry). */
	public Optional<Duration> sessionTimeout() {
		return sessionTimeout;
	}

	@Transactional
	public void setSessionTimeout(Optional<Duration> timeout) {
		AppSettings settings = repo.findSingleton().orElseGet(AppSettings::new);
		settings.setSessionTimeoutSeconds(timeout.map(Duration::toSeconds).orElse(null));
		repo.save(settings);
		this.sessionTimeout = timeout;
		reconcileActiveSessions(timeout);
	}

	/**
	 * Apply a timeout change to already-live sessions immediately: strip the TTL when switching to
	 * Never (otherwise the old countdown would still expire the "Never" session), or (re)apply the
	 * new TTL for a finite value. Without this, a finite→Never switch would still lock the session.
	 */
	private void reconcileActiveSessions(Optional<Duration> timeout) {
		Set<String> keys = redis.keys(SessionStore.KEY_PREFIX + "*");
		if (keys == null || keys.isEmpty()) {
			return;
		}
		for (String key : keys) {
			if (timeout.isPresent()) {
				redis.expire(key, timeout.get());
			} else {
				redis.persist(key);
			}
		}
	}

	private Optional<Duration> readFromDb() {
		Optional<AppSettings> row = repo.findSingleton();
		if (row.isEmpty()) {
			return Optional.of(defaultTimeout); // never configured → default
		}
		Long seconds = row.get().getSessionTimeoutSeconds();
		return seconds == null ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
	}
}
