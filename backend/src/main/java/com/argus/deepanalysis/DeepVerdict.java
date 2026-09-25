package com.argus.deepanalysis;

/** Agent 11's final call on a stock. */
public enum DeepVerdict {
	WORTH_BUYING("Worth buying"),
	NOT_WORTH_BUYING("Not worth buying"),
	WAIT("Wait — not yet");

	private final String label;

	DeepVerdict(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
