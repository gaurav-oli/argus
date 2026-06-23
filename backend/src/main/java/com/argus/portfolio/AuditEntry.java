package com.argus.portfolio;

import java.time.Instant;

/** A manual-edit audit entry as returned to the client (Story 3.7). */
public record AuditEntry(long id, String ticker, String action, String detail, Instant createdAt) {
}
