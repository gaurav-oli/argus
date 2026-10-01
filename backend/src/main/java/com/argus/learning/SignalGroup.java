package com.argus.learning;

/**
 * The kinds of evidence the agents contribute, shared by the recommender's policy and the Trade Learner so
 * both describe a call in the same terms ("news-led", "crowd-only", "chart + fundamentals agree"). {@code hard}
 * groups are company-specific evidence a call can rest on; the others (crowd, web, macro, calendar) can
 * corroborate but not carry a call by themselves.
 */
public enum SignalGroup {
	NEWS("company news", true),
	SOCIAL("crowd sentiment", false),
	INTERNET("web attention", false),
	INSIDER("insider activity", true),
	TECHNICAL("the chart (candlesticks, trend, volume)", true),
	FUNDAMENTAL("company fundamentals", true),
	FILINGS("filings and earnings reports", true),
	ACADEMIC("validated academic strategies", true),
	DEEP("Agent 11's deep analysis", true),
	MACRO("the macro backdrop", false),
	CALENDAR("earnings timing", false);

	private final String label;
	private final boolean hard;

	SignalGroup(String label, boolean hard) {
		this.label = label;
		this.hard = hard;
	}

	public String label() {
		return label;
	}

	public boolean hard() {
		return hard;
	}

	/** The group an agent's signal belongs to, from its id ({@code agent-1-news}, {@code agent-10-technical}, ...). */
	public static SignalGroup of(String agent) {
		if (agent.startsWith("agent-2-")) return SOCIAL;
		if (agent.startsWith("agent-3-")) return INTERNET;
		if (agent.startsWith("agent-4-")) return INSIDER;
		if (agent.startsWith("agent-7-")) return CALENDAR;
		if (agent.startsWith("agent-8-")) return MACRO;
		if (agent.startsWith("agent-10-")) return TECHNICAL;
		if (agent.startsWith("agent-11-")) return DEEP;
		if (agent.startsWith("agent-12-")) return FUNDAMENTAL;
		if (agent.startsWith("agent-14-")) return FILINGS;
		if (agent.startsWith("agent-15-")) return ACADEMIC;
		return NEWS;
	}
}
