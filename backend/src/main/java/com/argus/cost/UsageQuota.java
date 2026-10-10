package com.argus.cost;

import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * S-C3 (finding M1) — soft daily quotas so one invitee can't burn the household's model budget alone. Each
 * AI or import endpoint calls {@link #consume} before doing the work; over the cap it answers 429 with a
 * friendly message saying when it resets. Counters live in Redis ({@code argus:quota:<user>:<kind>:<date>},
 * expiring after two days). The admin is exempt by default. Fails open: if Redis is unreachable the call goes
 * ahead (the Cost Governor's monthly budget still applies to every paid call).
 */
@Service
@EnableConfigurationProperties(QuotaProperties.class)
public class UsageQuota {

	private static final Logger log = LoggerFactory.getLogger(UsageQuota.class);
	static final ZoneId ZONE = ZoneId.of("America/Toronto");

	public enum Kind {
		ASK_AI("Ask-AI messages"),
		RESEARCH("research jobs"),
		DEEP_ANALYSIS("deep analyses"),
		DEBATE("debates"),
		IMPORT("statement imports");

		private final String label;

		Kind(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private final StringRedisTemplate redis;
	private final QuotaProperties props;
	private final AppUserRepository users;

	public UsageQuota(StringRedisTemplate redis, QuotaProperties props, AppUserRepository users) {
		this.redis = redis;
		this.props = props;
		this.users = users;
	}

	/**
	 * Count one use of {@code kind} for the signed-in person, or throw 429 when it would go over today's cap.
	 * No-op with no signed-in person (a background job) or when quotas are off.
	 */
	public void consume(Kind kind) {
		Long userId = CurrentUserContext.get();
		int cap = capFor(userId, kind);
		if (cap <= 0) {
			return;
		}
		Long used;
		try {
			String key = key(userId, kind, today());
			used = redis.opsForValue().increment(key);
			if (used != null && used == 1L) {
				redis.expire(key, Duration.ofDays(2));
			}
		}
		catch (RuntimeException ex) {
			log.warn("Quota: counter unavailable for {} — allowing the call: {}", kind, ex.getMessage());
			return;
		}
		if (used != null && used > cap) {
			try {
				redis.opsForValue().decrement(key(userId, kind, today())); // a refused call doesn't count
			}
			catch (RuntimeException ignored) {
				// best effort
			}
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"You've used today's %d %s. It resets at midnight (Toronto time) — the shared AI budget is split between everyone on Argus."
							.formatted(cap, kind.label()));
		}
	}

	/** Today's usage and caps for the signed-in person (cap 0 = unlimited). */
	public record Usage(String kind, String label, int used, int cap) {
	}

	public Map<String, Usage> today(Long userId) {
		Map<String, Usage> out = new LinkedHashMap<>();
		for (Kind k : Kind.values()) {
			int used = 0;
			try {
				String v = userId == null ? null : redis.opsForValue().get(key(userId, k, today()));
				used = v == null ? 0 : Integer.parseInt(v);
			}
			catch (RuntimeException ignored) {
				// unknown → 0
			}
			out.put(k.name(), new Usage(k.name(), k.label(), used, Math.max(0, capFor(userId, k))));
		}
		return out;
	}

	/** The cap that applies to this person; 0 means unlimited. */
	int capFor(Long userId, Kind kind) {
		if (!props.enabled() || userId == null) {
			return 0;
		}
		int cap = props.capFor(kind);
		if (cap <= 0) {
			return 0;
		}
		boolean admin = users.findById(userId).map(u -> u.isAdmin()).orElse(false);
		if (admin) {
			return props.adminExempt() ? 0 : cap * props.adminMultiplier();
		}
		return cap;
	}

	static String key(Long userId, Kind kind, LocalDate day) {
		return "argus:quota:" + userId + ":" + kind.name() + ":" + day;
	}

	private static LocalDate today() {
		return LocalDate.now(ZONE);
	}
}
