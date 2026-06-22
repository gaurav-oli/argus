package com.argus.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the single {@link AppSettings} row. */
public interface AppSettingsRepository extends JpaRepository<AppSettings, Short> {

	default Optional<AppSettings> findSingleton() {
		return findById(AppSettings.SINGLETON_ID);
	}
}
