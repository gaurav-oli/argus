package com.argus.backup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * On-demand backup ("Back Up Now"). Runs {@code pg_dump} over TCP to the compose {@code postgres}
 * service — the backend can do this directly (unlike the scheduled host-side dumps in
 * {@code scripts/backup.sh}, which docker-exec into the postgres container) now that the backup
 * volume is mounted read-write. Writes into the same {@code full/} directory and filename pattern
 * (`argus-<yyyyMMdd-HHmmss>.sql.gz`) the scheduled job uses, so {@link BackupStatusService} and the
 * host's own 14-day retention prune treat a manual dump exactly like a scheduled one.
 *
 * <p>One dump at a time: a trigger while {@link TriggerState#RUNNING} is a no-op that just returns
 * the in-progress status, rather than queuing or racing a second {@code pg_dump}. State is in-memory
 * (not persisted) — a restart mid-dump loses the RUNNING/SUCCESS/FAILED history, which is fine, the
 * dump itself either completed (visible via {@link BackupStatusService}) or didn't.
 */
@Service
public class BackupTriggerService {

	private static final Logger log = LoggerFactory.getLogger(BackupTriggerService.class);
	private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	public enum TriggerState { IDLE, RUNNING, SUCCESS, FAILED }

	public record TriggerStatus(TriggerState state, Instant startedAt, String message) {
	}

	private final BackupProperties props;
	private final String pgUser;
	private final String pgPassword;
	private final String pgDb;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "backup-trigger");
		t.setDaemon(true);
		return t;
	});

	private volatile TriggerState state = TriggerState.IDLE;
	private volatile Instant startedAt;
	private volatile String message;

	public BackupTriggerService(BackupProperties props,
			@Value("${spring.datasource.username}") String pgUser,
			@Value("${spring.datasource.password}") String pgPassword,
			@Value("${POSTGRES_DB:argus}") String pgDb) {
		this.props = props;
		this.pgUser = pgUser;
		this.pgPassword = pgPassword;
		this.pgDb = pgDb;
	}

	public TriggerStatus status() {
		return new TriggerStatus(state, startedAt, message);
	}

	/** Start a dump if one isn't already running; always returns the (possibly just-updated) status
	 * immediately — the dump itself runs in the background. */
	public synchronized TriggerStatus trigger() {
		if (!props.enabled()) {
			state = TriggerState.FAILED;
			startedAt = Instant.now();
			message = "Backups are not configured (argus.backup.dir empty)";
			return status();
		}
		if (state == TriggerState.RUNNING) {
			return status();
		}
		state = TriggerState.RUNNING;
		startedAt = Instant.now();
		message = null;
		executor.submit(this::runDump);
		return status();
	}

	private void runDump() {
		String ts = TS_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.now());
		File fullDir = new File(props.dir(), "full");
		File out = new File(fullDir, "argus-" + ts + ".sql.gz");
		try {
			if (!fullDir.isDirectory() && !fullDir.mkdirs()) {
				throw new IOException("could not create " + fullDir);
			}
			dump(out);
			state = TriggerState.SUCCESS;
			message = out.getName();
			log.info("On-demand backup written: {}", out);
		}
		catch (Exception ex) {
			out.delete(); // don't leave a truncated/empty dump behind
			state = TriggerState.FAILED;
			message = ex.getMessage();
			log.warn("On-demand backup failed: {}", ex.getMessage());
		}
	}

	private void dump(File out) throws IOException, InterruptedException {
		ProcessBuilder pb = new ProcessBuilder("pg_dump", "-h", props.pgHost(), "-U", pgUser, pgDb);
		pb.environment().put("PGPASSWORD", pgPassword);
		pb.redirectErrorStream(false);
		Process proc = pb.start();
		try (InputStream in = proc.getInputStream();
				GZIPOutputStream gzipOut = new GZIPOutputStream(new FileOutputStream(out))) {
			in.transferTo(gzipOut);
		}
		String stderr = new String(proc.getErrorStream().readAllBytes());
		int exit = proc.waitFor();
		if (exit != 0) {
			throw new IOException("pg_dump exited " + exit + (stderr.isBlank() ? "" : ": " + stderr.strip()));
		}
	}
}
