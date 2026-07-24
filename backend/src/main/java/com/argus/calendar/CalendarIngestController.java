package com.argus.calendar;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for Agent 7's calendar ingest (session-gated under {@code /api/ops/calendar}),
 * mirroring the on-demand pattern used by Logic Review and Watchlist discovery. The run otherwise
 * fires automatically at 06:00 America/New_York.
 */
@RestController
@RequestMapping("/api/ops/calendar")
public class CalendarIngestController {

	private final Agent7CalendarService calendar;

	public CalendarIngestController(Agent7CalendarService calendar) {
		this.calendar = calendar;
	}

	@PostMapping("/run")
	public RunResult run() {
		return new RunResult(calendar.ingestOnce());
	}

	public record RunResult(int newEvents) {
	}
}
