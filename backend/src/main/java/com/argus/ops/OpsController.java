package com.argus.ops;

import com.argus.cost.CostRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small ops summary for the dashboard bottom strip (Epic 9) — the real numbers we have today:
 * how many agents are active, and cumulative paid (Haiku) spend this process. Session-gated.
 *
 * <p>RAM/SSD host telemetry is the Hardware Resource Monitor (Story 9.5) and isn't surfaced here.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

	/** Agents + paid spend at a glance. */
	public record OpsSummary(int agentsActive, int agentsTotal, double haikuSpendUsd) {
	}

	private final AgentStatusService agents;
	private final CostRecorder cost;

	public OpsController(AgentStatusService agents, CostRecorder cost) {
		this.agents = agents;
		this.cost = cost;
	}

	@GetMapping("/summary")
	public OpsSummary summary() {
		var fleet = agents.snapshot();
		int active = (int) fleet.stream().filter(a -> "ACTIVE".equals(a.status())).count();
		return new OpsSummary(active, fleet.size(), cost.totalUsd());
	}
}
