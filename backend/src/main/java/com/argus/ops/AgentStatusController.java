package com.argus.ops;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read endpoint behind the Agents dashboard (Epic 9, Story 9.1). Session-gated like all of /api. */
@RestController
@RequestMapping("/api/agents")
public class AgentStatusController {

	private final AgentStatusService service;

	public AgentStatusController(AgentStatusService service) {
		this.service = service;
	}

	/** Per-agent live status: counts, last-run, schedule, and any data-source note. */
	@GetMapping("/status")
	public List<AgentStatusView> status() {
		return service.snapshot();
	}
}
