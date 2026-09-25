package com.argus.recommendation;

/**
 * What Argus actually tells you to do. {@link #WATCH} is the honest "no clear edge" state — the old
 * model had no such state (a 50/50 read was forced into BULLISH and traded), which is why the
 * Intelligence page filled with 50–60% "recommendations" nobody would act on.
 */
public enum RecommendationAction {
	STRONG_BUY("Strong buy", SignalDirection.BULLISH),
	BUY("Buy", SignalDirection.BULLISH),
	WATCH("Watch — no clear edge", SignalDirection.NEUTRAL),
	AVOID("Avoid / sell", SignalDirection.BEARISH),
	STRONG_AVOID("Strong avoid / sell", SignalDirection.BEARISH);

	private final String label;
	private final SignalDirection direction;

	RecommendationAction(String label, SignalDirection direction) {
		this.label = label;
		this.direction = direction;
	}

	public String label() {
		return label;
	}

	public SignalDirection direction() {
		return direction;
	}

	/** Whether this is a call worth acting on (and worth opening a paper trade for). */
	public boolean actionable() {
		return this != WATCH;
	}
}
