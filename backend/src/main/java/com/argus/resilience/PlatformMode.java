package com.argus.resilience;

/**
 * Platform operating mode (Story 10.4 / GAP-3). {@code NORMAL} is full online operation; in
 * {@code DEGRADED} the internet is unreachable, so net-dependent ingestion should pause, the UI shows
 * last-known data with stale warnings, and local-only features (Ask-AI on Gemma, Agent 5 on cached
 * data) keep working.
 */
public enum PlatformMode {
	NORMAL,
	DEGRADED
}
