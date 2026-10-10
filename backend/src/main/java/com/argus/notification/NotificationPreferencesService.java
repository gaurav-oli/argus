package com.argus.notification;

import com.argus.security.CurrentUserContext;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The gate every Web Push producer consults before sending to a person. It applies <em>that person's</em>
 * notification preferences (S-C1: one row per user in {@code user_notification_prefs}; defaults when they have
 * none): per-type toggles (briefing / breaking / other alerts), quiet hours (local time — non-critical pushes
 * are held overnight), and per-ticker mutes. A CRITICAL-tier alert bypasses quiet hours but still respects the
 * type toggle and mutes. Each person's preferences are cached in memory (single-process monolith) and the cache
 * entry is replaced on save, so the hot-path {@link #allowFor} check rarely hits the DB.
 *
 * <p>A broadcast (no specific recipient) is filtered per device owner — see {@code PushService.sendToAll} with
 * a recipient filter — so one person turning breaking news off never silences it for anyone else.
 */
@Service
public class NotificationPreferencesService {

	public enum Category {
		BRIEFING, BREAKING, ALERT
	}

	private static final ZoneId ZONE = ZoneId.of("America/Toronto");
	private static final Snapshot DEFAULTS = new Snapshot(true, true, true, null, null, Set.of());

	private final JdbcTemplate jdbc;
	private final Map<Long, Snapshot> cache = new ConcurrentHashMap<>();

	public NotificationPreferencesService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private record Snapshot(boolean briefing, boolean breaking, boolean alerts, Integer quietStart,
			Integer quietEnd, Set<String> muted) {
	}

	/** The signed-in person's preferences for the settings UI. */
	public record View(boolean briefingEnabled, boolean breakingEnabled, boolean alertsEnabled,
			Integer quietStartHour, Integer quietEndHour, List<String> mutedTickers) {
	}

	/** The signed-in person's preferences (defaults when they have never saved any). */
	public View current() {
		Snapshot s = snapshot(CurrentUserContext.get());
		return new View(s.briefing(), s.breaking(), s.alerts(), s.quietStart(), s.quietEnd(),
				List.copyOf(s.muted()));
	}

	/** Save the signed-in person's preferences. Nobody else's change. */
	@Transactional
	public View update(View v) {
		Long userId = CurrentUserContext.get();
		if (userId == null) {
			throw new IllegalStateException("Notification preferences need a signed-in user");
		}
		String[] muted = v.mutedTickers() == null ? new String[0]
				: v.mutedTickers().stream().filter(t -> t != null && !t.isBlank())
						.map(t -> t.trim().toUpperCase()).distinct().toArray(String[]::new);
		jdbc.update(con -> {
			var ps = con.prepareStatement("""
					insert into user_notification_prefs (user_id, briefing_enabled, breaking_enabled, alerts_enabled,
					    quiet_start_hour, quiet_end_hour, muted_tickers, updated_at)
					values (?, ?, ?, ?, ?, ?, ?, now())
					on conflict (user_id) do update set briefing_enabled = excluded.briefing_enabled,
					    breaking_enabled = excluded.breaking_enabled, alerts_enabled = excluded.alerts_enabled,
					    quiet_start_hour = excluded.quiet_start_hour, quiet_end_hour = excluded.quiet_end_hour,
					    muted_tickers = excluded.muted_tickers, updated_at = now()""");
			ps.setLong(1, userId);
			ps.setBoolean(2, v.briefingEnabled());
			ps.setBoolean(3, v.breakingEnabled());
			ps.setBoolean(4, v.alertsEnabled());
			ps.setObject(5, hour(v.quietStartHour()));
			ps.setObject(6, hour(v.quietEndHour()));
			ps.setArray(7, con.createArrayOf("text", muted));
			return ps;
		});
		cache.remove(userId);
		return current();
	}

	/**
	 * Whether a push in this category (about these tickers, at this urgency) may go to {@code userId} right now.
	 * {@code tickers} may be null/empty for untargeted pushes (e.g. the briefing). A null user (a device
	 * registered before multi-user) gets the defaults.
	 */
	public boolean allowFor(Long userId, Category category, String[] tickers, boolean critical) {
		Snapshot s = snapshot(userId);
		boolean typeOn = switch (category) {
			case BRIEFING -> s.briefing();
			case BREAKING -> s.breaking();
			case ALERT -> s.alerts();
		};
		if (!typeOn) {
			return false;
		}
		if (tickers != null && tickers.length > 0 && allMuted(tickers, s.muted())) {
			return false;
		}
		return critical || !inQuietHours(s);
	}

	/** {@link #allowFor} for the person the current thread acts as (a request, or a job's {@code runAs}). */
	public boolean allow(Category category, String[] tickers, boolean critical) {
		return allowFor(CurrentUserContext.get(), category, tickers, critical);
	}

	public boolean allow(Category category) {
		return allow(category, null, false);
	}

	private Snapshot snapshot(Long userId) {
		if (userId == null) {
			return DEFAULTS;
		}
		return cache.computeIfAbsent(userId, this::readFromDb);
	}

	private static boolean allMuted(String[] tickers, Set<String> muted) {
		if (muted.isEmpty()) {
			return false;
		}
		return Arrays.stream(tickers).allMatch(t -> t != null && muted.contains(t.trim().toUpperCase()));
	}

	private static boolean inQuietHours(Snapshot s) {
		if (s.quietStart() == null || s.quietEnd() == null || s.quietStart().equals(s.quietEnd())) {
			return false;
		}
		int h = ZonedDateTime.now(ZONE).getHour();
		int start = s.quietStart();
		int end = s.quietEnd();
		return start < end ? (h >= start && h < end) : (h >= start || h < end); // handle midnight wrap
	}

	private static Short hour(Integer h) {
		return h == null ? null : (short) Math.floorMod(h, 24);
	}

	private Snapshot readFromDb(Long userId) {
		List<Snapshot> rows = jdbc.query("""
				select briefing_enabled, breaking_enabled, alerts_enabled, quiet_start_hour, quiet_end_hour, muted_tickers
				  from user_notification_prefs where user_id = ?""", (rs, i) -> {
			java.sql.Array arr = rs.getArray("muted_tickers");
			String[] m = arr == null ? new String[0] : (String[]) arr.getArray();
			Set<String> muted = Arrays.stream(m).map(t -> t.trim().toUpperCase()).collect(Collectors.toSet());
			Integer qs = rs.getObject("quiet_start_hour") == null ? null : rs.getInt("quiet_start_hour");
			Integer qe = rs.getObject("quiet_end_hour") == null ? null : rs.getInt("quiet_end_hour");
			return new Snapshot(rs.getBoolean("briefing_enabled"), rs.getBoolean("breaking_enabled"),
					rs.getBoolean("alerts_enabled"), qs, qe, muted);
		}, userId);
		return rows.isEmpty() ? DEFAULTS : rows.get(0);
	}
}
