package com.argus.conversation;

/** The investor's self-declared risk tolerance (Story 7.6), ordered low → high. Persisted as its name. */
public enum RiskTolerance {
	CONSERVATIVE,
	BALANCED,
	GROWTH,
	AGGRESSIVE;

	/** Human-friendly label for the chat grounding, e.g. "Growth". */
	public String label() {
		return name().charAt(0) + name().substring(1).toLowerCase();
	}

	/** Lenient parse of a user-supplied value; null/blank → null, unknown → IllegalArgumentException. */
	public static RiskTolerance fromInput(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return RiskTolerance.valueOf(value.trim().toUpperCase());
	}
}
