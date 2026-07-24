package com.argus.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent runtime configuration ({@code argus.agent.*}).
 *
 * @param pollIntervalMs   scheduled poll interval in milliseconds
 * @param readCount        max records read per agent per poll (also the max pending entries
 *                         inspected for reclaim per tick)
 * @param pelReclaimIdleMs how long a delivered-but-unacknowledged message must sit idle before
 *                         it's reclaimed and redispatched (Epic 1 hardening backlog — Story 1.5,
 *                         crash recovery). Deliberately well above the Model Gateway's own
 *                         call-timeout ceiling (150s default) — a handler that legitimately calls
 *                         the BIG-tier model can take nearly that long on its own, and reclaiming
 *                         a message that's still being genuinely (if slowly) processed would cause
 *                         it to be handled twice. Default 5 minutes: ~2x that ceiling, so recovery
 *                         is about not losing events across a crash/restart, not fast retry.
 * @param dedupeTtlHours   how long a processed event's id is remembered to skip a redelivered
 *                         duplicate (e.g. a reclaim that races a slow-but-successful original
 *                         handler) from re-running its side effects
 * @param maxDeliveryAttempts a message that has already been delivered this many times (Redis's
 *                         own XPENDING delivery count, not a separately-tracked counter) is no
 *                         longer reclaimed — it stays pending permanently instead. Without this
 *                         cap, a message that fails the same way every time (a poison/undecodable
 *                         record, an envelope version this build will never understand, or a
 *                         handler bug that always throws) gets reclaimed and redispatched on
 *                         essentially every tick forever once it first goes idle, hammering Redis
 *                         and the handler indefinitely instead of settling into the same
 *                         "quarantined, visible for inspection" state a first-time poison record
 *                         already gets.
 */
@ConfigurationProperties("argus.agent")
public record AgentProperties(
		@DefaultValue("500") long pollIntervalMs,
		@DefaultValue("10") int readCount,
		@DefaultValue("300000") long pelReclaimIdleMs,
		@DefaultValue("24") long dedupeTtlHours,
		@DefaultValue("5") int maxDeliveryAttempts) {
}
