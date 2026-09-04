package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Demo Mode setting: cache-backed reads, persisted writes, shares the app_settings singleton row
 * with {@link SettingsService} rather than a new table. */
class DemoModeServiceTest {

	private final AppSettingsRepository repo = mock(AppSettingsRepository.class);

	@Test
	void defaultsToDemoModeOffWhenNoRowExistsYet() {
		when(repo.findSingleton()).thenReturn(Optional.empty());
		DemoModeService service = new DemoModeService(repo);

		service.load();

		assertFalse(service.current().demoMode());
	}

	@Test
	void loadsThePersistedValueOnStartup() {
		AppSettings existing = new AppSettings();
		existing.setDemoMode(true);
		when(repo.findSingleton()).thenReturn(Optional.of(existing));
		DemoModeService service = new DemoModeService(repo);

		service.load();

		assertTrue(service.current().demoMode());
	}

	@Test
	void updatePersistsAndRefreshesTheCacheWithoutNeedingAReload() {
		AppSettings existing = new AppSettings();
		when(repo.findSingleton()).thenReturn(Optional.of(existing));
		DemoModeService service = new DemoModeService(repo);
		service.load();
		assertFalse(service.current().demoMode());

		DemoModeService.View result = service.update(new DemoModeService.View(true));

		assertTrue(result.demoMode());
		assertTrue(service.current().demoMode(), "cache must reflect the update immediately");
		verify(repo).save(existing);
	}

	@Test
	void updateCreatesTheRowWhenNoneExistsYet() {
		when(repo.findSingleton()).thenReturn(Optional.empty());
		DemoModeService service = new DemoModeService(repo);
		service.load();

		DemoModeService.View result = service.update(new DemoModeService.View(true));

		assertTrue(result.demoMode());
		verify(repo).save(any(AppSettings.class));
	}
}
