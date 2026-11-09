package com.argus.grading;

import java.time.LocalDate;
import java.util.List;

/** Shared arithmetic for the pass tests: per-call results pooled into independent clusters (see {@link ReportCardMath}). */
public final class GateMath {

	/** Pre-registered: every pass test needs at least this many independent clusters. */
	public static final int MIN_CLUSTERS = 30;

	private GateMath() {
	}

	/** One call's outcome: {@code value} already direction-adjusted (positive = the call was right), in points. */
	public record Outcome(LocalDate date, String sector, double value) {
	}

	/** Cluster-pooled mean and count for a list of outcomes measured over {@code horizonDays}. */
	public static ReportCardMath.Grade pooled(List<Outcome> outcomes, int horizonDays) {
		return ReportCardMath.grade(outcomes.stream()
				.map(o -> new ReportCardMath.Obs(o.date(), o.sector(), 1, 1, o.value(), null, false)).toList(), horizonDays);
	}

	public static String json(Object... kv) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i + 1 < kv.length; i += 2) {
			if (sb.length() > 1) sb.append(',');
			sb.append('"').append(kv[i]).append("\":");
			Object v = kv[i + 1];
			if (v == null) sb.append("null");
			else if (v instanceof Number || v instanceof Boolean) {
				sb.append(v instanceof Double d ? String.format(java.util.Locale.ROOT, "%.4f", d) : v);
			}
			else sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return sb.append('}').toString();
	}
}
