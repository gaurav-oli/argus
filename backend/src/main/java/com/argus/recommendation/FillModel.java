package com.argus.recommendation;

/**
 * Realistic trading costs (strategy rebuild, Phase 0). Paper and shadow trades used to fill at exactly the quoted
 * price on both ends, which made every result look slightly better than a real account could have done. Each side
 * now pays a commission, half the bid/ask spread, and slippage that grows with how much the stock normally moves.
 * Gaps through a stop are handled where the stop fires (it fills at the gapped price), not here.
 *
 * <p>All numbers are percent of notional, per side, and deliberately conservative: a cost model that flatters the
 * strategy defeats the point of measuring it.
 */
public final class FillModel {

	/** Per-side commission, as a percent of notional. */
	static final double COMMISSION_PCT = 0.02;
	/** Slippage per side as a fraction of the stock's normal daily range (ATR %). */
	static final double SLIPPAGE_ATR_FRACTION = 0.05;
	/** Slippage assumed when the stock's normal range isn't known. */
	static final double DEFAULT_SLIPPAGE_PCT = 0.10;
	static final double MAX_SLIPPAGE_PCT = 0.50;

	private FillModel() {
	}

	/** Half the quoted spread, by price bucket: cheap stocks trade with much wider spreads. */
	static double halfSpreadPct(double price) {
		if (price < 10) {
			return 0.15;
		}
		return price < 50 ? 0.04 : 0.02;
	}

	/** Cost of one side (entry or exit), in percent. */
	public static double perSidePct(double price, Double atrPct) {
		double slippage = atrPct == null || atrPct <= 0 ? DEFAULT_SLIPPAGE_PCT
				: Math.min(MAX_SLIPPAGE_PCT, SLIPPAGE_ATR_FRACTION * atrPct);
		return COMMISSION_PCT + halfSpreadPct(price) + slippage;
	}

	/** Annual cost of borrowing shares to short, in percent (easy-to-borrow large caps; hard-to-borrow costs far more). */
	static final double BORROW_PCT_PER_YEAR = 1.0;

	/** Borrow cost for a short held {@code holdDays} trading days, in percent (strategy rebuild, Phase 4). */
	public static double borrowPct(int holdDays) {
		return BORROW_PCT_PER_YEAR * Math.max(1, holdDays) / 252.0;
	}

	/** Cost of a full round trip (entry + exit), in percent — what gets subtracted from a trade's return. */
	public static double roundTripPct(double price, Double atrPct) {
		return 2 * perSidePct(price, atrPct);
	}
}
