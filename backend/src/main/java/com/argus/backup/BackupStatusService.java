package com.argus.backup;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Filesystem-derived backup status (Story 10.2 — no DB table needed, survives restarts). Reads the
 * backup dir the host's launchd jobs write into ({@code full/} 6-hourly, {@code critical/} 15-min):
 * newest dump per kind, its age vs the expected cadence, and whether the destination is present at
 * all (a missing/unwritable dir = the drive is disconnected or the mount is broken).
 */
@Service
public class BackupStatusService {

	private final BackupProperties props;

	public BackupStatusService(BackupProperties props) {
		this.props = props;
	}

	/** One kind of dump (full/critical): newest file's time+size and whether it's overdue. */
	public record KindStatus(Instant lastSuccessAt, Long lastSizeBytes, boolean stale) {
	}

	public record BackupStatusView(boolean enabled, boolean destinationConnected,
			KindStatus full, KindStatus critical) {
	}

	public BackupStatusView status() {
		if (!props.enabled()) {
			return new BackupStatusView(false, false, null, null);
		}
		File dir = new File(props.dir());
		// The dir is mounted read-only in the container, so "connected" = exists and is readable.
		boolean connected = dir.isDirectory() && dir.canRead();
		return new BackupStatusView(true, connected,
				kindStatus(new File(dir, "full"), props.fullStaleSeconds()),
				kindStatus(new File(dir, "critical"), props.criticalStaleSeconds()));
	}

	private static KindStatus kindStatus(File dir, long staleSeconds) {
		Optional<File> newest = newestDump(dir);
		Instant last = newest.map(f -> Instant.ofEpochMilli(f.lastModified())).orElse(null);
		boolean stale = last == null
				|| Duration.between(last, Instant.now()).getSeconds() > staleSeconds;
		return new KindStatus(last, newest.map(File::length).orElse(null), stale);
	}

	private static Optional<File> newestDump(File dir) {
		File[] files = dir.listFiles((d, n) -> n.endsWith(".sql.gz"));
		return files == null ? Optional.empty()
				: Arrays.stream(files).max(Comparator.comparingLong(File::lastModified));
	}
}
