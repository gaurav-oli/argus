package com.argus.resilience;

import java.time.Instant;

/**
 * Current platform mode for the UI (Story 10.4). When {@code DEGRADED}, the dashboard shows a banner
 * and last-known data.
 *
 * @param mode   {@code NORMAL} | {@code DEGRADED}
 * @param since  when the current mode began
 * @param reason short human explanation (e.g. "internet unreachable")
 */
public record PlatformModeView(String mode, Instant since, String reason) {
}
