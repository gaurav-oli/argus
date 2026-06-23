package com.argus.recommendation;

/**
 * An agent's directional view on a stock (Story 6.1). {@link #sign()} feeds the auditable scoring
 * engine: bullish pulls the probability up, bearish down, neutral abstains (carries no directional
 * weight).
 */
public enum SignalDirection {
	BULLISH(1),
	BEARISH(-1),
	NEUTRAL(0);

	private final int sign;

	SignalDirection(int sign) {
		this.sign = sign;
	}

	public int sign() {
		return sign;
	}
}
