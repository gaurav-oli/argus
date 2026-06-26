package com.argus.cost;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent 6 — Cost Governor (Epic 10, FR-45/46). Tracks month-to-date paid-API spend against a
 * monthly budget and exposes the posture (NORMAL → NOTICE 70% → WARNING 80% → CRITICAL 95%).
 * Notification-first: the user decides as thresholds are crossed; the hard fallback is at 95%, where
 * {@link #allowPaidCall()} returns false and the Model Gateway auto-switches escalations to the
 * local model. A budget of 0 (or negative) disables governance (unlimited).
 */
@Service
public class CostGovernor {

	private static final ZoneId ZONE = ZoneId.of("America/Toronto");
	private static final double CRITICAL = 0.95;
	private static final double WARNING = 0.80;
	private static final double NOTICE = 0.70;

	private final CostEventRepository events;
	private final double budgetUsd;

	public CostGovernor(CostEventRepository events, @Value("${argus.budget.monthly-usd:20}") double budgetUsd) {
		this.events = events;
		this.budgetUsd = budgetUsd;
	}

	/** True while paid (Haiku) calls are within budget; false at ≥95% → auto-switch to local. */
	public boolean allowPaidCall() {
		return budgetUsd <= 0 || monthToDate() < budgetUsd * CRITICAL;
	}

	public BudgetStatus status() {
		double spent = monthToDate();
		long calls = events.countByOccurredAtAfter(startOfMonth());
		double pct = budgetUsd <= 0 ? 0 : (spent / budgetUsd) * 100;
		LocalDate today = LocalDate.now(ZONE);
		int dayOfMonth = today.getDayOfMonth();
		int daysInMonth = today.lengthOfMonth();
		double projected = dayOfMonth == 0 ? spent : spent / dayOfMonth * daysInMonth;
		String band = pct >= 95 ? "CRITICAL" : pct >= 80 ? "WARNING" : pct >= 70 ? "NOTICE" : "NORMAL";
		return new BudgetStatus(round(spent), budgetUsd, round(pct), band, YearMonth.now(ZONE).toString(),
				daysInMonth - dayOfMonth, round(projected), pct >= 95, calls);
	}

	private double monthToDate() {
		return events.sumCostSince(startOfMonth()).doubleValue();
	}

	private static Instant startOfMonth() {
		return YearMonth.now(ZONE).atDay(1).atStartOfDay(ZONE).toInstant();
	}

	private static double round(double v) {
		return Math.round(v * 1_000_000.0) / 1_000_000.0;
	}
}
