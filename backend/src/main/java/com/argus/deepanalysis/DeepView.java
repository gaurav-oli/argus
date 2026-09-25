package com.argus.deepanalysis;

/**
 * The slice of Agent 11's latest verdict the other agents need — the recommender's policy, the Investor and
 * Agent 9 — without exposing the entity. {@code ageDays} lets consumers discount a stale verdict.
 */
public record DeepView(DeepVerdict verdict, Integer holdDays, int conviction, String headline, String invalidation, long ageDays) {
}
