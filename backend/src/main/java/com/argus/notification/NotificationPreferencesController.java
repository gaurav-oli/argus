package com.argus.notification;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notification preferences (session-gated under {@code /api/notifications/preferences}): which push
 * categories are on, quiet hours, and muted tickers. Read by the settings screen; every push producer
 * enforces them via {@link NotificationPreferencesService}.
 */
@RestController
@RequestMapping("/api/notifications/preferences")
public class NotificationPreferencesController {

	private final NotificationPreferencesService prefs;

	public NotificationPreferencesController(NotificationPreferencesService prefs) {
		this.prefs = prefs;
	}

	@GetMapping
	public NotificationPreferencesService.View get() {
		return prefs.current();
	}

	@PutMapping
	public NotificationPreferencesService.View update(@RequestBody NotificationPreferencesService.View body) {
		return prefs.update(body);
	}
}
