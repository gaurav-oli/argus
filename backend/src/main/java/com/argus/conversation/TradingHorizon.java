package com.argus.conversation;

/** How the investor likes to hold a position (first-login onboarding + Story 7.6 profile), used to
 * lean recommendation framing toward CORE_HOLD-style calls or shorter WATCH-style ones. Persisted as
 * its name. */
public enum TradingHorizon {
	LONG_TERM_HOLDER,
	ACTIVE_TRADER,
	MIX;

	/** Human-friendly label, e.g. "Long-term holder". */
	public String label() {
		return switch (this) {
			case LONG_TERM_HOLDER -> "Long-term holder";
			case ACTIVE_TRADER -> "Active trader";
			case MIX -> "A mix of both";
		};
	}

	/** Lenient parse of a user-supplied value; null/blank → null, unknown → IllegalArgumentException. */
	public static TradingHorizon fromInput(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return TradingHorizon.valueOf(value.trim().toUpperCase());
	}
}
