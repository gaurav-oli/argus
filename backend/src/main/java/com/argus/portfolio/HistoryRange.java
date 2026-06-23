package com.argus.portfolio;

import com.argus.common.BadRequestException;
import java.time.LocalDate;

/** Selectable chart ranges (Story 3.6, FR-4). Accepts the UI labels (1D/1W/1M/3M/YTD/1Y/All). */
public enum HistoryRange {

	D1, W1, M1, M3, YTD, Y1, ALL;

	/** Inclusive start date for this range relative to {@code today}; null = all history. */
	public LocalDate startFrom(LocalDate today) {
		return switch (this) {
			case D1 -> today;
			case W1 -> today.minusWeeks(1);
			case M1 -> today.minusMonths(1);
			case M3 -> today.minusMonths(3);
			case YTD -> today.withDayOfYear(1);
			case Y1 -> today.minusYears(1);
			case ALL -> null;
		};
	}

	public static HistoryRange fromParam(String raw) {
		if (raw == null || raw.isBlank()) {
			return M1;
		}
		return switch (raw.trim().toUpperCase()) {
			case "1D", "D1" -> D1;
			case "1W", "W1" -> W1;
			case "1M", "M1" -> M1;
			case "3M", "M3" -> M3;
			case "YTD" -> YTD;
			case "1Y", "Y1" -> Y1;
			case "ALL" -> ALL;
			default -> throw new BadRequestException("Unknown range: " + raw);
		};
	}
}
