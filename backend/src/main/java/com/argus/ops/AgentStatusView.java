package com.argus.ops;

import java.time.Instant;

/**
 * One agent's live status for the Agents / Operations dashboard (Epic 9, Story 9.1).
 *
 * @param id           stable client id (e.g. {@code "news"})
 * @param code         fleet label (e.g. {@code "Agent 1"})
 * @param name         display name
 * @param description  one-line "what it does"
 * @param status       {@code ACTIVE} (has captured data) or {@code IDLE} (scheduled, nothing yet)
 * @param captured     count of items this agent has captured/produced
 * @param captureLabel unit for {@code captured} (e.g. {@code "articles"})
 * @param lastActivity most-recent capture time, or {@code null} if it has never run
 * @param schedule     human cadence (e.g. {@code "every 6h"})
 * @param note         optional dependency/degradation hint, or {@code null}
 */
public record AgentStatusView(String id, String code, String name, String description,
		String status, long captured, String captureLabel, Instant lastActivity,
		String schedule, String note) {
}
