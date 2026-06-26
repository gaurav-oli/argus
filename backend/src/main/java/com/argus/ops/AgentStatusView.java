package com.argus.ops;

import java.time.Instant;

/**
 * One agent's status for the Agents / Operations dashboard (Epic 9, Story 9.1). Mirrors the
 * architecture's 7-agent roster: Phase-1 agents (1 News, 5 Recommender, 7 Calendar) run; Agents 2/3/4
 * and full Agent 6 are planned (Phase 2/3).
 *
 * @param id           stable client id
 * @param code         fleet label ({@code "Agent 1"})
 * @param name         display name
 * @param description  one-line "what it does"
 * @param status       {@code ACTIVE} | {@code IDLE} | {@code PARTIAL} | {@code PLANNED}
 * @param captured     items captured/produced (0 for planned)
 * @param captureLabel unit for {@code captured}
 * @param lastActivity most-recent capture time, or {@code null}
 * @param schedule     human cadence
 * @param note         optional dependency/spend hint, or {@code null}
 * @param phase        roadmap phase for not-yet-built agents (e.g. {@code "Phase 2"}), or {@code null}
 */
public record AgentStatusView(String id, String code, String name, String description,
		String status, long captured, String captureLabel, Instant lastActivity, String schedule,
		String note, String phase) {
}
