package com.argus.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Backup status/alerting config (Stories 10.1/10.2), plus the connection details
 * {@link BackupTriggerService} needs for an on-demand dump. The scheduled dumps still run on the
 * HOST via launchd + {@code scripts/backup.sh}; the app reads {@code dir} — the backup directory
 * mounted into the container — to derive status and raise staleness/disconnect alerts, and (since
 * the on-demand "Back Up Now" button) can also write a fresh dump into it directly via {@code pg_dump}
 * over TCP to {@code pgHost}. Empty {@code dir} disables the whole feature, trigger included.
 */
@ConfigurationProperties("argus.backup")
public record BackupProperties(
		@DefaultValue("") String dir,
		/** 🟡 when the newest FULL dump is older than this (6h cadence + slack). */
		@DefaultValue("25200") long fullStaleSeconds,       // 7h
		/** 🟡 when the newest CRITICAL dump is older than this (15-min cadence + slack). */
		@DefaultValue("2700") long criticalStaleSeconds,    // 45m
		/** Compose service name for Postgres — pg_dump connects here over TCP for on-demand backups. */
		@DefaultValue("postgres") String pgHost) {

	public boolean enabled() {
		return !dir.isBlank();
	}
}
