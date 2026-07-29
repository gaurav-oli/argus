package com.argus.ops;

import com.argus.backup.BackupStatusService;
import com.argus.backup.BackupTriggerService;
import com.argus.cost.CostRecorder;
import com.argus.resilience.PlatformModeService;
import com.argus.resilience.PlatformModeView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
	private final PlatformModeService platformMode;
	private final StorageService storage;
	private final BackupStatusService backup;
	private final BackupTriggerService backupTrigger;

	public OpsController(AgentStatusService agents, CostRecorder cost, HardwareService hardware,
			FreshnessService freshness, PlatformModeService platformMode, StorageService storage,
			BackupStatusService backup, BackupTriggerService backupTrigger) {
		this.agents = agents;
		this.cost = cost;
		this.hardware = hardware;
		this.freshness = freshness;
		this.platformMode = platformMode;
		this.storage = storage;
		this.backup = backup;
		this.backupTrigger = backupTrigger;
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

	/** Story 10.4 — current platform mode (NORMAL/DEGRADED) for the dashboard banner. */
	@GetMapping("/platform-mode")
	public PlatformModeView platformMode() {
		return platformMode.current();
	}

	/** Per-agent data-storage inventory: rows + on-disk size per agent and the tables it owns. */
	@GetMapping("/storage")
	public StorageService.StorageView storage() {
		return storage.snapshot();
	}

	/** Story 10.2 — backup status: newest full/critical dump, size, staleness, destination presence,
	 * plus the on-demand trigger's own RUNNING/SUCCESS/FAILED state (for the "Back Up Now" button). */
	@GetMapping("/backup")
	public BackupView backup() {
		return new BackupView(backup.status(), backupTrigger.status());
	}

	/** Kick off an on-demand backup ("Back Up Now"). Returns immediately — the dump runs in the
	 * background; poll {@code GET /backup} for the trigger state to reach SUCCESS/FAILED. */
	@PostMapping("/backup/trigger")
	public BackupView triggerBackup() {
		backupTrigger.trigger();
		return new BackupView(backup.status(), backupTrigger.status());
	}

	public record BackupView(BackupStatusService.BackupStatusView status, BackupTriggerService.TriggerStatus trigger) {
	}
}
