package com.argus.ops;

import com.argus.cost.CostRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operations endpoints (Epic 9), session-gated under {@code /api/ops}: the dashboard bottom-strip
 * summary (active agents + paid Haiku spend), host hardware telemetry (Story 9.5), and data-source
 * freshness (Story 9.7).
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

	/** Agents + paid spend at a glance. */
	public record OpsSummary(int agentsActive, int agentsTotal, double haikuSpendUsd) {
	}

	private final AgentStatusService agents;
	private final CostRecorder cost;
	private final HardwareService hardware;
	private final FreshnessService freshness;

	public OpsController(AgentStatusService agents, CostRecorder cost, HardwareService hardware,
			FreshnessService freshness) {
		this.agents = agents;
		this.cost = cost;
		this.hardware = hardware;
		this.freshness = freshness;
	}

	@GetMapping("/summary")
	public OpsSummary summary() {
		var fleet = agents.snapshot();
		int active = (int) fleet.stream().filter(a -> "ACTIVE".equals(a.status())).count();
		return new OpsSummary(active, fleet.size(), cost.totalUsd());
	}

	/** Story 9.5 — live host RAM / SSD / CPU telemetry. */
	@GetMapping("/hardware")
	public HardwareMetrics hardware() {
		return hardware.snapshot();
	}

	/** Story 9.7 — per-source last-update times with stale flags. */
	@GetMapping("/freshness")
	public FreshnessView freshness() {
		return freshness.snapshot();
	}
}
