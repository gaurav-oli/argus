package com.argus.fundamentals;

import com.argus.fundamentals.Fundamentals.ValuationView;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic <b>reverse DCF</b>: instead of guessing a fair price from assumed growth, ask what growth the
 * <em>current price</em> already assumes, and compare it with the growth the company has actually delivered. "Is
 * the good news already priced in?" — the question a quality company at a bad price fails. Pure arithmetic, no
 * LLM (the README's rule: numbers are model-derived, never free-hand).
 *
 * <p><b>Model.</b> Earnings per share grow for {@value #YEARS} years at a rate that fades linearly from the solved
 * starting growth {@code g} to the terminal rate, then a Gordon terminal value; cash flows are a fixed fraction
 * ({@value #CONVERSION}) of earnings (owner-earnings proxy — reinvestment needs are ignored, so absolute values are
 * flattering and only the <em>relative</em> comparison of implied vs. delivered growth is meaningful). The discount
 * rate is {@code riskFree + beta × equityRiskPremium}. The start growth is solved by bisection so that the modelled
 * value equals the price.
 */
public final class ValuationAnalyzer {

	static final int YEARS = 10;
	static final double TERMINAL_GROWTH = 0.025;
	static final double CONVERSION = 0.70;
	static final double EQUITY_RISK_PREMIUM = 0.05;
	static final double DEFAULT_RISK_FREE = 0.043;
	/** Implied-minus-delivered growth (percentage points) beyond which a stock reads rich / cheap. */
	static final double VERDICT_GAP_PTS = 8.0;
	private static final double LOW = -0.20;
	private static final double HIGH = 0.80;

	private ValuationAnalyzer() {
	}

	/**
	 * @param price           current share price
	 * @param epsTtm          trailing 12-month earnings per share
	 * @param beta            equity beta (clamped 0.7–1.8); null → 1.0
	 * @param riskFreePct     10-year yield in percent (e.g. 4.3); null → {@value #DEFAULT_RISK_FREE}
	 * @param deliveredGrowth growth the company has actually delivered, in percent per year; null if unknown
	 */
	public static Optional<ValuationView> analyze(Double price, Double epsTtm, Double beta, Double riskFreePct, Double deliveredGrowth) {
		if (price == null || price <= 0 || epsTtm == null || epsTtm <= 0) {
			return Optional.empty(); // no positive earnings → an earnings-based valuation does not apply
		}
		double rf = riskFreePct == null ? DEFAULT_RISK_FREE : riskFreePct / 100.0;
		double b = beta == null ? 1.0 : Math.max(0.7, Math.min(1.8, beta));
		double r = rf + b * EQUITY_RISK_PREMIUM;

		double implied;
		String bound = "";
		if (value(HIGH, epsTtm, r) < price) {
			implied = HIGH;
			bound = "+";
		}
		else if (value(LOW, epsTtm, r) > price) {
			implied = LOW;
			bound = "-";
		}
		else {
			double lo = LOW, hi = HIGH;
			for (int i = 0; i < 60; i++) {
				double mid = (lo + hi) / 2;
				if (value(mid, epsTtm, r) < price) lo = mid;
				else hi = mid;
			}
			implied = (lo + hi) / 2;
		}
		double impliedPct = implied * 100;
		String verdict = "FAIR";
		Double gap = null;
		if (deliveredGrowth != null) {
			gap = impliedPct - deliveredGrowth;
			verdict = gap >= VERDICT_GAP_PTS ? "RICH" : gap <= -VERDICT_GAP_PTS ? "CHEAP" : "FAIR";
		}
		String summary = String.format(Locale.ROOT,
				"At $%.2f the market is pricing in about %s%.0f%% annual earnings growth (fading to %.1f%% over %d years, %.1f%% discount rate)%s.",
				price, bound.equals("+") ? "over " : bound.equals("-") ? "under " : "", Math.abs(impliedPct), TERMINAL_GROWTH * 100, YEARS, r * 100,
				deliveredGrowth == null ? "; the company's delivered growth is unknown, so no rich/cheap call is made"
						: String.format(Locale.ROOT, "; the company has delivered about %.0f%% — %s", deliveredGrowth,
								verdict.equals("RICH") ? "the price already assumes more than it has delivered"
										: verdict.equals("CHEAP") ? "the price assumes less than it has delivered" : "roughly in line"));
		return Optional.of(new ValuationView(round1(impliedPct), deliveredGrowth == null ? null : round1(deliveredGrowth), round1(r * 100),
				gap == null ? null : round1(gap), verdict, price, epsTtm, summary));
	}

	/** Modelled per-share value if EPS grows from {@code g0} (fading to terminal) for {@value #YEARS} years. */
	static double value(double g0, double eps0, double r) {
		double eps = eps0;
		double pv = 0;
		for (int t = 1; t <= YEARS; t++) {
			double g = g0 + (TERMINAL_GROWTH - g0) * (t - 1) / (YEARS - 1);
			eps *= 1 + g;
			pv += eps * CONVERSION / Math.pow(1 + r, t);
		}
		double terminal = eps * (1 + TERMINAL_GROWTH) * CONVERSION / (r - TERMINAL_GROWTH);
		return pv + terminal / Math.pow(1 + r, YEARS);
	}

	private static double round1(double v) {
		return Math.round(v * 10.0) / 10.0;
	}
}
