package com.argus.regime;

/**
 * Coarse sector buckets — just fine-grained enough that a macro theme can hit one very differently
 * from another (a tariff shock is not a rate shock is not an oil shock). Each carries the ETF/index
 * used as its "how did this sector actually trade today" benchmark by {@link MarketRegimeService}.
 */
public enum Sector {
	TECHNOLOGY("Technology", "XLK"),
	SEMICONDUCTORS("Semiconductors", "SMH"),
	AI_INFRA("AI infrastructure", "QQQ"),
	COMMUNICATION("Communication & media", "XLC"),
	CONSUMER_DISCRETIONARY("Consumer discretionary", "XLY"),
	CONSUMER_STAPLES("Consumer staples", "XLP"),
	AUTOS_EV("Autos & EV", "XLY"),
	ENERGY("Energy", "XLE"),
	FINANCIALS("Financials", "XLF"),
	HEALTHCARE("Healthcare", "XLV"),
	INDUSTRIALS_DEFENSE("Industrials, space & defense", "XLI"),
	MATERIALS("Materials", "XLB"),
	UTILITIES("Utilities", "XLU"),
	REAL_ESTATE("Real estate", "XLRE"),
	GOLD("Gold", "GLD"),
	BROAD_MARKET("Broad market", "SPY"),
	BROAD_GROWTH("Broad growth (Nasdaq)", "QQQ"),
	CANADIAN_EQUITY("Canadian equity", "^GSPTSE"),
	OTHER("Other", "SPY");

	private final String label;
	private final String benchmark;

	Sector(String label, String benchmark) {
		this.label = label;
		this.benchmark = benchmark;
	}

	public String label() {
		return label;
	}

	/** Symbol whose daily move stands in for "the sector". */
	public String benchmark() {
		return benchmark;
	}
}
