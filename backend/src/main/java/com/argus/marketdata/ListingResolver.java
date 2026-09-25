package com.argus.marketdata;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which Yahoo symbol(s) to use for a ticker. A bare symbol can silently resolve to a <em>different</em>
 * US instrument — bare {@code DOL} is a US ETF at ~$75, not Dollarama at C$180 — so Toronto-listed
 * holdings are declared explicitly ({@code argus.market.tsx-tickers}) and always use the {@code .TO}
 * listing. A live price quoted in CAD is a secondary hint (try {@code .TO} first, bare as a fallback).
 */
@Component
public class ListingResolver {

	private final Set<String> tsx;

	public ListingResolver(@Value("${argus.market.tsx-tickers:VFV,VDY,XQQ,ZGLD,DOL}") String tsxTickers) {
		this.tsx = Arrays.stream(tsxTickers.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
	}

	public boolean isTsx(String ticker) {
		return ticker != null && tsx.contains(ticker.trim().toUpperCase(Locale.ROOT));
	}

	/** Yahoo symbols to try, in order. */
	public List<String> yahooSymbols(String ticker, boolean liveQuotedInCad) {
		if (isTsx(ticker)) {
			return List.of(ticker + ".TO");
		}
		return liveQuotedInCad ? List.of(ticker + ".TO", ticker) : List.of(ticker);
	}
}
