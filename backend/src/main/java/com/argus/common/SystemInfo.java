package com.argus.common;

import java.time.Instant;

/** Sample typed response DTO (returned directly as JSON — no envelope). */
public record SystemInfo(String name, String version, String profile, Instant time) {
}
