package com.argus.security;

import com.argus.common.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App settings endpoints (Story 2.3), session-gated under {@code /api/**}. Session timeout is
 * global — writes are admin-only (S-A3 / H1).
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	/** Sane upper bound for a finite timeout (24h); use Never for longer. */
	private static final long MAX_SECONDS = 86_400;
	private static final long MIN_SECONDS = 60;

	private final SettingsService settings;
	private final CurrentUserService currentUser;

	public SettingsController(SettingsService settings, CurrentUserService currentUser) {
		this.settings = settings;
		this.currentUser = currentUser;
	}

	@GetMapping("/session-timeout")
	public SessionTimeout getSessionTimeout() {
		return new SessionTimeout(settings.sessionTimeout().map(Duration::toSeconds).orElse(null));
	}

	@PutMapping("/session-timeout")
	public ResponseEntity<Void> setSessionTimeout(@RequestBody(required = false) SessionTimeout body,
			HttpServletRequest request) {
		currentUser.requireAdmin(request);
		if (body == null) {
			throw new BadRequestException("Missing request body");
		}
		Long seconds = body.seconds();
		if (seconds != null && (seconds < MIN_SECONDS || seconds > MAX_SECONDS)) {
			throw new BadRequestException("Timeout must be null (Never) or between 60 and 86400 seconds");
		}
		settings.setSessionTimeout(seconds == null ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds)));
		return ResponseEntity.noContent().build();
	}

	/** Session idle timeout in seconds; {@code null} = Never. */
	public record SessionTimeout(Long seconds) {
	}
}
