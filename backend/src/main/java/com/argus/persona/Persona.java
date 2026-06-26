package com.argus.persona;

/** The four MVP investor personas (F11). Each weighs in on every recommendation. */
public enum Persona {

	BUFFETT("Warren Buffett", "Value & durable moats; long-term ownership, circle of competence"),
	LYNCH("Peter Lynch", "Growth at a reasonable price; invest in what you understand"),
	DEVILS_ADVOCATE("Devil's Advocate", "Argues the bear case and the risks, whatever the call says"),
	CANADIAN("Canadian Investor", "TFSA/RRSP/RESP tax efficiency, CAD/USD exposure, US withholding tax");

	private final String displayName;
	private final String lens;

	Persona(String displayName, String lens) {
		this.displayName = displayName;
		this.lens = lens;
	}

	public String displayName() {
		return displayName;
	}

	public String lens() {
		return lens;
	}

	/** Lenient parse of a model-emitted key (e.g. "buffett", "devils_advocate", "canadian"). */
	static Persona fromKey(String key) {
		if (key == null) {
			return null;
		}
		String k = key.trim().toUpperCase().replace(' ', '_').replace("'", "").replace("-", "_");
		for (Persona p : values()) {
			if (p.name().equals(k) || p.displayName().toUpperCase().contains(k) || k.contains(p.name())) {
				return p;
			}
		}
		return null;
	}
}
