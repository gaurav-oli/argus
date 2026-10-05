package com.argus.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-only usage stats + invite management for the friends on this Argus instance (Multi-user
 * Phase 1). The stats view deliberately carries no portfolio or dollar figures at all — by design, no
 * one (including the admin) can see another person's holdings; it's engagement only: who's signed up,
 * how often they show up, and roughly how long they stick around.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private final CurrentUserService currentUser;
	private final AppUserRepository users;
	private final UserActivityService activity;
	private final InvitedEmailRepository invites;
	private final InviteEmailService inviteEmail;

	public AdminController(CurrentUserService currentUser, AppUserRepository users, UserActivityService activity,
			InvitedEmailRepository invites, InviteEmailService inviteEmail) {
		this.currentUser = currentUser;
		this.users = users;
		this.activity = activity;
		this.invites = invites;
		this.inviteEmail = inviteEmail;
	}

	@GetMapping("/users")
	public List<UserStatsView> users(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return users.findAllByOrderByCreatedAtAsc().stream().map(this::viewFor).toList();
	}

	/** Everyone allowed to sign in, oldest invite first — {@code joined} is true once they actually have. */
	@GetMapping("/invites")
	public List<InviteView> invites(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return invites.findAllByOrderByInvitedAtAsc().stream().map(this::viewFor).toList();
	}

	/** Allow a new email to sign in with Google. Idempotent — inviting an already-invited email is a no-op. */
	@PostMapping("/invites")
	public InviteView invite(@RequestBody InviteRequest body, HttpServletRequest request) {
		AppUser admin = currentUser.requireAdmin(request);
		String email = requireEmail(body == null ? null : body.email());
		InvitedEmail saved = invites.findById(email).orElseGet(() -> invites.save(new InvitedEmail(email, admin.getEmail())));
		return viewFor(saved);
	}

	/** Actually email the invite (Resend) — the link carries this person's own tracking token, so a
	 * later visit to it (before they've even signed in) shows up as "opened". 503 if Resend isn't
	 * configured or the send itself fails, so the admin UI can show that clearly, not silently. */
	@PostMapping("/invites/send")
	public InviteView sendInvite(@RequestBody InviteRequest body, HttpServletRequest request) {
		AppUser admin = currentUser.requireAdmin(request);
		String email = requireEmail(body == null ? null : body.email());
		InvitedEmail invited = invites.findById(email).orElseGet(() -> invites.save(new InvitedEmail(email, admin.getEmail())));
		String token = invited.ensureToken();
		invites.save(invited); // persist the token before the network call, so a failed send still has one
		try {
			inviteEmail.send(email, token, admin.getName());
		}
		catch (InviteEmailException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
		}
		invited.markEmailSent();
		return viewFor(invites.save(invited));
	}

	private static String requireEmail(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
		}
		return InvitedEmail.normalize(raw);
	}

	private InviteView viewFor(InvitedEmail i) {
		return new InviteView(i.getEmail(), i.getInvitedAt(), users.findByEmailIgnoreCase(i.getEmail()).isPresent(),
				i.getEmailSentAt(), i.getOpenedAt());
	}

	public record InviteRequest(String email) {
	}

	/** {@code emailSentAt}/{@code openedAt} are null until the admin sends it / the person visits the
	 * link — {@code joined} (from {@code app_user}) is the one that actually matters. */
	public record InviteView(String email, Instant invitedAt, boolean joined, Instant emailSentAt, Instant openedAt) {
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
