package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only usage stats for the friends on this Argus instance (Multi-user Phase 1). Deliberately
 * carries no portfolio or dollar figures at all — by design, no one (including the admin) can see
 * another person's holdings; this view is engagement only: who's signed up, how often they show up,
 * and roughly how long they stick around.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final CurrentUserService currentUser;
	private final AppUserRepository users;
	private final UserActivityService activity;

	public AdminController(CurrentUserService currentUser, AppUserRepository users, UserActivityService activity) {
		this.currentUser = currentUser;
		this.users = users;
		this.activity = activity;
	}

	@GetMapping("/users")
	public List<UserStatsView> users(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return users.findAllByOrderByCreatedAtAsc().stream().map(this::viewFor).toList();
	}

	private UserStatsView viewFor(AppUser u) {
		List<UserActivityDay> days = activity.historyFor(u.getId());
		// "Active minutes" = the sum of each day's first-to-last-request span — an honest proxy for
		// time spent, not a claim of continuous attention (see UserActivityDay's own javadoc).
		long totalActiveMinutes = days.stream()
				.mapToLong(d -> Duration.between(d.getFirstSeenAt(), d.getLastSeenAt()).toMinutes())
				.sum();
		Instant lastActiveAt = days.stream().map(UserActivityDay::getLastSeenAt).max(Instant::compareTo).orElse(null);
		return new UserStatsView(u.getName(), u.getEmail(), u.getPictureUrl(), u.isAdmin(), u.getCreatedAt(),
				u.getLastLoginAt(), u.getLoginCount(), days.size(), totalActiveMinutes, lastActiveAt);
	}

	/** No portfolio/financial fields here, by design — see the class javadoc. */
	public record UserStatsView(String name, String email, String pictureUrl, boolean admin, Instant joinedAt,
			Instant lastLoginAt, int loginCount, int activeDays, long totalActiveMinutes, Instant lastActiveAt) {
	}
}
