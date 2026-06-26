package com.argus.persona;

/** A persona's stance toward a recommendation. */
public enum PersonaStance {

	AGREE,
	DISAGREE,
	CAUTION;

	/** Lenient parse of a model-emitted stance; anything unrecognized reads as CAUTION. */
	static PersonaStance fromText(String text) {
		if (text == null) {
			return CAUTION;
		}
		String t = text.trim().toUpperCase();
		for (PersonaStance s : values()) {
			if (t.contains(s.name())) {
				return s;
			}
		}
		return CAUTION;
	}
}
