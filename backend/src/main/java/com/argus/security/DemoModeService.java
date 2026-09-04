package com.argus.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo Mode: hide the Portfolio section and mask $ amounts/holdings elsewhere, for showing the
 * product to someone else without exposing real financial data. Shares the single {@code
 * app_settings} row ({@link AppSettings}, Story 2.3) rather than a new table — that entity's own
 * javadoc calls out "future stories add columns to this row," and this is exactly that. Cached in
 * memory (single-process monolith, same shape as {@link SettingsService}) so the frontend's
 * on-every-page-load read never hits the DB.
 */
@Service
public class DemoModeService {

	private final AppSettingsRepository repo;
	private volatile boolean demoModeCache;

	public DemoModeService(AppSettingsRepository repo) {
		this.repo = repo;
	}

	public record View(boolean demoMode) {
	}

	@PostConstruct
	void load() {
		this.demoModeCache = repo.findSingleton().map(AppSettings::isDemoMode).orElse(false);
	}

	public View current() {
		return new View(demoModeCache);
	}

	@Transactional
	public View update(View v) {
		AppSettings settings = repo.findSingleton().orElseGet(AppSettings::new);
		settings.setDemoMode(v.demoMode());
		repo.save(settings);
		this.demoModeCache = v.demoMode();
		return current();
	}
}
