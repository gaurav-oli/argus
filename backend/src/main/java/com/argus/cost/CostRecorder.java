package com.argus.cost;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records the cost of paid cloud-model calls (Story 7.3 — "the cost is recorded"). Minimal by
 * design: it computes per-call USD from token usage and emits a structured log line, and keeps an
 * in-memory running total for in-process visibility/tests.
 *
 * <p><b>Scope:</b> persisted per-call/agent/model spend, the monthly reset, and the 70/80/95%
 * budget thresholds + auto-switch are the <b>Cost Governor (Epic 10, Stories 10.5/10.6)</b> — not
 * this class. Claude Haiku 4.5 pricing: input $1.00 / output $5.00 per million tokens.
 */
@Component
public class CostRecorder {

	private static final Logger log = LoggerFactory.getLogger(CostRecorder.class);

	private static final double INPUT_USD_PER_TOKEN = 1.0 / 1_000_000;
	private static final double OUTPUT_USD_PER_TOKEN = 5.0 / 1_000_000;

	/** Running total in micro-USD (1e-6 USD) to keep the accumulator integer-exact. */
	private final AtomicLong totalMicroUsd = new AtomicLong();
	private volatile double lastCostUsd;

	/** Persists each call so spend survives restarts (Agent 6). Null in unit tests (in-memory only). */
	private final CostEventRepository events;

	public CostRecorder(org.springframework.beans.factory.ObjectProvider<CostEventRepository> events) {
		this.events = events.getIfAvailable();
	}

	/** Record one Haiku call; returns its USD cost. Negative counts are clamped to 0. */
	public double record(String model, long inputTokens, long outputTokens) {
		long in = Math.max(0, inputTokens);
		long out = Math.max(0, outputTokens);
		double cost = in * INPUT_USD_PER_TOKEN + out * OUTPUT_USD_PER_TOKEN;
		this.lastCostUsd = cost;
		this.totalMicroUsd.addAndGet(Math.round(cost * 1_000_000));
		log.info("event=haiku_escalation model={} inputTokens={} outputTokens={} costUsd={}",
				model, in, out, String.format("%.6f", cost));
		if (events != null) {
			try {
				events.save(new CostEvent(model, "haiku_escalation", in, out, java.math.BigDecimal.valueOf(cost)));
			}
			catch (RuntimeException ex) {
				log.warn("Could not persist cost event: {}", ex.getMessage());
			}
		}
		return cost;
	}

	/** Cost of the most recent recorded call (USD). 0 before any call. */
	public double lastCostUsd() {
		return lastCostUsd;
	}

	/** Cumulative recorded spend this process lifetime (USD). */
	public double totalUsd() {
		return totalMicroUsd.get() / 1_000_000.0;
	}
}
