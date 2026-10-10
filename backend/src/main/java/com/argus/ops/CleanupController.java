package com.argus.ops;

import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smart Cleanup agent endpoints (session-gated under {@code /api/ops/cleanup}). {@code POST /preview}
 * and {@code POST /run} are admin-only (S-A3). {@code GET /last} stays readable by any signed-in user
 * for the Ops panel.
 */
@RestController
@RequestMapping("/api/ops/cleanup")
public class CleanupController {

	private final CleanupService cleanup;
	private final JdbcTemplate jdbc;
	private final CurrentUserService currentUser;

	public CleanupController(CleanupService cleanup, JdbcTemplate jdbc, CurrentUserService currentUser) {
		this.cleanup = cleanup;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** Dry-run: compute the keep/delete/roll-up plan, change nothing. Admin-only (S-A3). */
	@PostMapping("/preview")
	public CleanupService.CleanupReport preview(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return cleanup.preview();
	}

	/** Live: roll up then delete the disposable firehose rows. Admin-only (S-A3). */
	@PostMapping("/run")
	public CleanupService.CleanupReport run(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return cleanup.run();
	}

	/** The most recent run (dry-run or live), or null if the agent has never run. */
	@GetMapping("/last")
	public LastRun last() {
		return jdbc.query(
				"select started_at, dry_run, deleted_rows, kept_rows, rolled_up_days, freed_bytes, summary"
						+ " from cleanup_run order by started_at desc limit 1",
				rs -> rs.next()
						? new LastRun(rs.getTimestamp("started_at").toInstant(), rs.getBoolean("dry_run"),
								rs.getLong("deleted_rows"), rs.getLong("kept_rows"), rs.getLong("rolled_up_days"),
								rs.getLong("freed_bytes"), rs.getString("summary"))
						: null);
	}

	public record LastRun(Instant startedAt, boolean dryRun, long deletedRows, long keptRows, long rolledUpDays,
			long freedBytes, String summary) {
	}
}
