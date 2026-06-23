package com.argus.portfolio;

import com.argus.common.BadRequestException;

/** Kinds of corporate action (Story 3.3, FR-1c). Persisted as the lowercase {@link #code()}. */
public enum CorporateActionType {

	SPLIT("split"),
	REVERSE_SPLIT("reverse_split"),
	STOCK_DIVIDEND("stock_dividend"),
	TICKER_CHANGE("ticker_change"),
	MERGER("merger");

	private final String code;

	CorporateActionType(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	/** True when this type can be applied purely by scaling lot shares (total cost preserved). */
	public boolean isShareScaling() {
		return this == SPLIT || this == REVERSE_SPLIT;
	}

	public static CorporateActionType fromCode(String code) {
		for (CorporateActionType t : values()) {
			if (t.code.equalsIgnoreCase(code)) {
				return t;
			}
		}
		throw new BadRequestException("Unknown corporate action type: " + code);
	}
}
