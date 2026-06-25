package com.argus.ops;

import java.time.Instant;

/**
 * One item in the dashboard Live Alerts feed (Epic 9). A normalized view over the agents' real
 * signals — Stranger Danger warnings, upcoming calendar events, and fresh recommendations.
 *
 * @param id     stable client key
 * @param tier   {@code critical} | {@code warning} | {@code info}
 * @param title  short headline
 * @param body   one-line detail
 * @param source which agent/feature raised it
 * @param ticker related symbol, or {@code null}
 * @param time   when it occurred / is scheduled
 */
public record AlertView(String id, String tier, String title, String body, String source,
		String ticker, Instant time) {
}
