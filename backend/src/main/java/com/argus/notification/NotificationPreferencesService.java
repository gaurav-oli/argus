package com.argus.notification;

import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single gate every Web Push producer consults before fanning out. It applies the user's
 * notification preferences: per-type toggles (briefing / breaking / other alerts), quiet hours
 * (local time — non-critical pushes are held overnight), and per-ticker mutes. A CRITICAL-tier alert
 * bypasses quiet hours but still respects the master type toggle and mutes. Preferences are cached in
 * memory (single-process monolith) so the hot-path {@link #allow} check never hits the DB.
 */
@Service
public class NotificationPreferencesService {

	public enum Category {
		BRIEFING, BREAKING, ALERT
	}

	private static final ZoneId ZONE = ZoneId.of("America/Toronto");

	private final NotificationPrefsRepository repo;
	private volatile Snapshot cache = new Snapshot(true, true, true, null, null, Set.of());

	public NotificationPreferencesService(NotificationPrefsRepository repo) {
		this.repo = repo;
	}

	private record Snapshot(boolean briefing, boolean breaking, boolean alerts, Integer quietStart,
			Integer quietEnd, Set<String> muted) {
	}

	/** The current preferences for the settings UI. */
	public record View(boolean briefingEnabled, boolean breakingEnabled, boolean alertsEnabled,
			Integer quietStartHour, Integer quietEndHour, List<String> mutedTickers) {
	}

	@PostConstruct
	void load() {
		this.cache = readFromDb();
	}

	public View current() {
		Snapshot s = cache;
		return new View(s.briefing(), s.breaking(), s.alerts(), s.quietStart(), s.quietEnd(),
				List.copyOf(s.muted()));
	}

	@Transactional
	public View update(View v) {
		NotificationPrefs prefs = repo.findSingleton().orElseGet(NotificationPrefs::new);
		String[] muted = v.mutedTickers() == null ? new String[0]
				: v.mutedTickers().stream().filter(t -> t != null && !t.isBlank())
						.map(t -> t.trim().toUpperCase()).distinct().toArray(String[]::new);
		prefs.update(v.briefingEnabled(), v.breakingEnabled(), v.alertsEnabled(),
				hour(v.quietStartHour()), hour(v.quietEndHour()), muted);
		repo.save(prefs);
		this.cache = readFromDb();
		return current();
	}

	/**
	 * Whether a push in this category (about these tickers, at this urgency) may go out right now.
	 * {@code tickers} may be null/empty for untargeted pushes (e.g. the briefing).
	 */
	public boolean allow(Category category, String[] tickers, boolean critical) {
		Snapshot s = cache;
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

	public boolean allow(Category category) {
		return allow(category, null, false);
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

	private Snapshot readFromDb() {
		NotificationPrefs p = repo.findSingleton().orElse(null);
		if (p == null) {
			return new Snapshot(true, true, true, null, null, Set.of());
		}
		Set<String> muted = p.getMutedTickers() == null ? Set.of()
				: Arrays.stream(p.getMutedTickers()).map(t -> t.trim().toUpperCase()).collect(Collectors.toSet());
		Integer qs = p.getQuietStartHour() == null ? null : p.getQuietStartHour().intValue();
		Integer qe = p.getQuietEndHour() == null ? null : p.getQuietEndHour().intValue();
		return new Snapshot(p.isBriefingEnabled(), p.isBreakingEnabled(), p.isAlertsEnabled(), qs, qe, muted);
	}
}
