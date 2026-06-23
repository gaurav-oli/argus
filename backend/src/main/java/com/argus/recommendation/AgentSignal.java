package com.argus.recommendation;

/**
 * One agent's input to a recommendation (Story 6.1): its {@code direction}, the {@code weight} it
 * carries (a non-negative importance/strength), and a human-readable {@code rationale}. These are the
 * explicit inputs the scoring engine combines — every probability is traceable back to a list of these.
 *
 * @param agent     the contributing agent (e.g. "agent-1-news", "agent-7-calendar")
 * @param direction bullish / bearish / neutral
 * @param weight    non-negative weight; clamped to 0 if negative
 * @param rationale why the agent reached this view (shown in the diagnostic, Story 6.2)
 */
public record AgentSignal(String agent, SignalDirection direction, double weight, String rationale) {

	public AgentSignal {
		if (weight < 0) {
			weight = 0;
		}
	}
}
