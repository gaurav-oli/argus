package com.argus.ops;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * When each clock-scheduled job last completed, in {@code agent_runs} under {@code job:<Class>.<method>}.
 * Written for every cron job by the scheduler itself ({@code AgentConfig}), so no job has to remember to
 * do it; read by {@link MissedRunCatchUp} to re-run a job whose slot passed while the host was down.
 */
@Component
public class ScheduledRunLedger {

	private static final Logger log = LoggerFactory.getLogger(ScheduledRunLedger.class);
	static final String PREFIX = "job:";

	private final JdbcTemplate jdbc;

	public ScheduledRunLedger(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Stamp a completed run of {@code job} ({@code fully.qualified.Class.method}). Never throws. */
	public void recordRun(String job) {
		try {
			jdbc.update("insert into agent_runs (agent_id, last_run_at) values (?, now()) "
					+ "on conflict (agent_id) do update set last_run_at = excluded.last_run_at", PREFIX + job);
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't record a run of {}: {}", job, ex.getMessage());
		}
	}

	public Optional<Instant> lastRun(String job) {
		return jdbc.query("select last_run_at from agent_runs where agent_id = ?",
				(rs, n) -> rs.getTimestamp(1).toInstant(), PREFIX + job).stream().findFirst();
	}
}
