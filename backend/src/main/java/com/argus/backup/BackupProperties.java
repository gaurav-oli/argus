package com.argus.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Backup status/alerting config (Stories 10.1/10.2). The dumps themselves run on the HOST via
 * launchd + {@code scripts/backup.sh} (the backend is containerized and can't docker-exec pg_dump);
 * the app only reads {@code dir} — the backup directory mounted read-only into the container — to
 * derive status and raise staleness/disconnect alerts. Empty {@code dir} disables the feature.
 */
@ConfigurationProperties("argus.backup")
public record BackupProperties(
		@DefaultValue("") String dir,
		/** 🟡 when the newest FULL dump is older than this (6h cadence + slack). */
		@DefaultValue("25200") long fullStaleSeconds,       // 7h
		/** 🟡 when the newest CRITICAL dump is older than this (15-min cadence + slack). */
		@DefaultValue("2700") long criticalStaleSeconds) {  // 45m

	public boolean enabled() {
		return !dir.isBlank();
	}
}
