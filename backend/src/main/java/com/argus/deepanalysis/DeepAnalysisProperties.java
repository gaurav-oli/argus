package com.argus.deepanalysis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 11 configuration ({@code argus.deep-analysis.*}).
 *
 * @param enabled              master switch (the nightly run and the boot pass)
 * @param refreshDays          a ticker's verdict is re-done when older than this
 * @param signalTtl            how long a finished verdict keeps feeding the quick recommender
 * @param maxPerRun            cap on tickers enqueued by one nightly run
 * @param stageGap             pause between LLM stages so interactive callers (Ask AI, personas) are not starved
 * @param useHaikuForVerdict   whether the final verdict call may escalate to paid Claude Haiku (budget-governed;
 *                             falls back to the local model when unavailable or over budget)
 * @param dipAlertDrawdownPct  drawdown from the 60-day high (negative %) at which a WORTH_BUYING verdict is
 *                             announced as a "possible buying opportunity"
 */
@ConfigurationProperties("argus.deep-analysis")
public record DeepAnalysisProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("3") int refreshDays,
		@DefaultValue("4d") Duration signalTtl,
		@DefaultValue("40") int maxPerRun,
		@DefaultValue("15s") Duration stageGap,
		@DefaultValue("true") boolean useHaikuForVerdict,
		@DefaultValue("-8.0") double dipAlertDrawdownPct) {
}
