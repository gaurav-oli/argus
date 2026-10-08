package com.argus.ops;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * How often each scheduled agent is expected to show new activity — the single source of truth for
 * both the Agents page pipeline (which animates by cadence and recency) and {@link FreshnessService}
 * (which raises the stale-ingestion alert), so the two can never disagree about what "stalled" means.
 *
 * <ul>
 *   <li>{@code interval} — the nominal run cadence. Drives how busy an agent's pipeline wire looks.</li>
 *   <li>{@code staleAfter} — how long a gap can get before it is genuinely stale: cadence plus a
 *       generous buffer for market hours, weekends and naturally quiet stretches (see the comment in
 *       {@link FreshnessService#snapshot()}), so a healthy quiet period never reads as broken.</li>
 * </ul>
 *
 * Agents with no fixed cadence (Agent 9 runs only on request; Agent 6 is continuous and has no
 * last-activity timestamp) have no entry, and are never shown as stalled.
 */
public enum AgentCadence {

	NEWS("news", Duration.ofMinutes(5), Duration.ofMinutes(30)),
	SOCIAL("social", Duration.ofMinutes(10), Duration.ofHours(1)),
	INTERNET("internet", Duration.ofHours(6), Duration.ofHours(12)),
	FILINGS("filings", Duration.ofHours(1), Duration.ofHours(72)),
	RECOMMENDER("recommender", Duration.ofHours(6), Duration.ofHours(12)),
	CALENDAR("calendar", Duration.ofHours(2), Duration.ofHours(72)),
	/** Tags Agent 1's articles, but only macro stories count, and those can be hours apart. */
	MACRO("macro", Duration.ofMinutes(5), Duration.ofHours(24)),
	/** Weekday after-close candles: a long weekend is a normal 3–4 day gap. */
	TECHNICAL("technical", Duration.ofDays(1), Duration.ofDays(4)),
	DEEP("deep", Duration.ofDays(1), Duration.ofHours(72)),
	FUNDAMENTALS("fundamentals", Duration.ofDays(1), Duration.ofDays(4)),
	LEARNER("learner", Duration.ofDays(1), Duration.ofHours(72)),
	/** Runs every 2h, but a digest only appears when a company actually files something new. */
	FILINGS_READER("filings-reader", Duration.ofHours(2), Duration.ofDays(7)),
	/** Its timestamp is the monthly library import. */
	STRATEGIES("strategies", Duration.ofDays(30), Duration.ofDays(35));

	private final String agentId;
	private final Duration interval;
	private final Duration staleAfter;

	AgentCadence(String agentId, Duration interval, Duration staleAfter) {
		this.agentId = agentId;
		this.interval = interval;
		this.staleAfter = staleAfter;
	}

	public String agentId() {
		return agentId;
	}

	public Duration interval() {
		return interval;
	}

	public Duration staleAfter() {
		return staleAfter;
	}

	/** The cadence for an {@link AgentStatusView#id()}, or empty for agents with no fixed cadence. */
	public static Optional<AgentCadence> forAgent(String agentId) {
		return Arrays.stream(values()).filter(c -> c.agentId.equals(agentId)).findFirst();
	}
}
