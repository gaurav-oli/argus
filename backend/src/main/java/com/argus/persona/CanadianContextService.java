package com.argus.persona;

import com.argus.conversation.InvestorProfileService;
import com.argus.marketdata.FxRateService;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Builds the factual "Canadian investor" context that grounds the Canadian persona (Story 7.5, FR-34):
 * the live USD/CAD rate, the CAD-equivalent of a US-listed name's price target, and the registered-account
 * tax treatment (TFSA/RRSP/RESP eligibility + US dividend withholding). These are DETERMINISTIC facts the
 * model states — not numbers it invents — so the Canadian lens is accurate rather than plausible-sounding.
 *
 * <p>Withholding treatment (Canada–US treaty): US dividends carry a 15% withholding tax that is
 * <b>waived in an RRSP</b> (a treaty-recognized retirement account), <b>withheld and unrecoverable</b> in a
 * TFSA or RESP, and recoverable via the foreign tax credit only in a non-registered account. Capital gains
 * are sheltered in TFSA/RRSP/RESP and taxable only in non-registered accounts.
 */
@Service
public class CanadianContextService {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final FxRateService fx;
	private final LivePortfolioService livePortfolio;
	private final InvestorProfileService investorProfile;

	public CanadianContextService(FxRateService fx, LivePortfolioService livePortfolio,
			InvestorProfileService investorProfile) {
		this.fx = fx;
		this.livePortfolio = livePortfolio;
		this.investorProfile = investorProfile;
	}

	/**
	 * A short facts block for {@code ticker} to feed the Canadian persona. {@code priceTargetNative} is the
	 * recommendation's price target in the ticker's own currency (may be null). The TFSA/RRSP + withholding
	 * lens applies only to a Canadian-resident investor (from the saved profile, Story 7.6); for a
	 * non-Canadian resident it is suppressed. The CAD-equivalent line is included only when the name is
	 * US-listed, the investor's home currency is CAD, and today's rate is available.
	 */
	public String describe(String ticker, BigDecimal priceTargetNative) {
		String residency = investorProfile.residency();
		if (!"Canadian".equalsIgnoreCase(residency)) {
			return "- Investor residency: " + residency + " — the Canadian TFSA/RRSP/RESP + US-withholding "
					+ "lens does not apply; assess this against the investor's own tax regime.";
		}

		if (!isUsListed(ticker)) {
			return "- Canadian-listed (CAD): no US dividend withholding tax; eligible in TFSA, RRSP, RESP "
					+ "and non-registered accounts. Capital gains are sheltered in registered accounts.";
		}

		StringBuilder b = new StringBuilder();
		if ("CAD".equalsIgnoreCase(investorProfile.homeCurrency())) {
			Optional<BigDecimal> rate = fx.usdCadOn(LocalDate.now(TORONTO));
			if (rate.isPresent()) {
				b.append(String.format("- USD/CAD ≈ %.4f (Bank of Canada, latest).", rate.get()));
				if (priceTargetNative != null) {
					BigDecimal cad = priceTargetNative.multiply(rate.get()).setScale(2, RoundingMode.HALF_UP);
					b.append(String.format(" Price target US$%.2f ≈ C$%s.", priceTargetNative, cad));
				}
				b.append('\n');
			}
		}
		b.append("- US-listed: eligible in TFSA, RRSP, RESP and non-registered accounts. US dividends carry "
				+ "a 15% withholding tax (Canada–US treaty) — waived in an RRSP, but withheld and "
				+ "unrecoverable in a TFSA or RESP; recoverable via the foreign tax credit only in a "
				+ "non-registered account. Capital gains are sheltered in TFSA/RRSP/RESP, taxable in "
				+ "non-registered.");
		return b.toString();
	}

	/**
	 * A name is treated as US-listed when its live price is quoted in USD (Finnhub) rather than CAD (the TSX
	 * {@code .TO} feed). Unpriced tickers default to US-listed — most recommendations are US names, and the
	 * withholding note is the safer default to surface.
	 */
	private boolean isUsListed(String ticker) {
		return livePortfolio.priceCurrency(ticker)
				.map(currency -> !"CAD".equalsIgnoreCase(currency))
				.orElse(true);
	}
}
