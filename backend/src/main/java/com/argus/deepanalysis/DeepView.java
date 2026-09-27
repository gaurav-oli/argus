package com.argus.deepanalysis;

/**
 * The slice of Agent 11's latest verdict the other agents need — the recommender's policy, the Investor and Agent 9 — without
 * exposing the entity. {@code ageDays} lets consumers discount a stale verdict; {@code atRisk} means the thesis tracker has seen
 * new information that undermines it (a broken price level, a bad new filing) and a re-analysis is queued — consumers must not
 * keep leaning on such a verdict.
 */
public record DeepView(DeepVerdict verdict, Integer holdDays, int conviction, String headline, String invalidation, long ageDays, boolean atRisk,
		String atRiskReason, Double invalidationPrice) {

	/** Legacy arity (no thesis status). */
	public DeepView(DeepVerdict verdict, Integer holdDays, int conviction, String headline, String invalidation, long ageDays) {
		this(verdict, holdDays, conviction, headline, invalidation, ageDays, false, null, null);
	}
}
