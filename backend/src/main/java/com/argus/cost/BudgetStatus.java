package com.argus.cost;

/**
 * Month-to-date budget posture for Agent 6 (Cost Governor).
 *
 * @param spentUsd         paid (cloud-model) spend this calendar month
 * @param budgetUsd        the monthly budget ceiling
 * @param percentUsed      spent / budget, 0–100+
 * @param band             {@code NORMAL} | {@code NOTICE} (≥70%) | {@code WARNING} (≥80%) | {@code CRITICAL} (≥95%)
 * @param month            the calendar month, e.g. {@code 2026-06}
 * @param daysLeftInMonth  days remaining in the month
 * @param projectedUsd     linear month-end projection at the current run-rate
 * @param paidCallsBlocked true once ≥95% — escalations auto-switch to the local model
 * @param paidCalls        number of paid (Haiku) calls this month
 * @param localModelCalls  number of successful local-model (Gemma) calls this month — free, but worth
 *                         showing alongside spend so the panel isn't only "how much did I spend."
 */
public record BudgetStatus(double spentUsd, double budgetUsd, double percentUsed, String band,
		String month, int daysLeftInMonth, double projectedUsd, boolean paidCallsBlocked, long paidCalls,
		long localModelCalls) {
}
