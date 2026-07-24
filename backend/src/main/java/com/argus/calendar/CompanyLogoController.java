package com.argus.calendar;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch ticker -> logo URL lookup for UI surfaces outside the calendar (e.g. the Holdings table),
 * backed by the same cache {@link CompanyLogoService} warms during Agent 7's ingest. Session-gated
 * like all of /api. Read-only; never triggers a fresh Finnhub fetch — a ticker with nothing cached
 * yet is simply omitted from the response (the caller falls back to an initial-letter icon).
 */
@RestController
@RequestMapping("/api/market")
public class CompanyLogoController {

	private final CompanyLogoRepository logos;

	public CompanyLogoController(CompanyLogoRepository logos) {
		this.logos = logos;
	}

	/** {@code ?tickers=AAPL,MSFT,...} -> {@code {"AAPL": "https://...", "MSFT": "https://..."}} (misses omitted). */
	@GetMapping("/logos")
	public Map<String, String> logos(@RequestParam String tickers) {
		Set<String> distinct = Arrays.stream(tickers.split(","))
				.map(String::trim)
				.filter(t -> !t.isEmpty())
				.map(String::toUpperCase)
				.collect(Collectors.toSet());
		if (distinct.isEmpty()) {
			return Map.of();
		}
		List<CompanyLogo> found = logos.findAllByTickerIn(distinct);
		Map<String, String> out = new HashMap<>();
		for (CompanyLogo logo : found) {
			if (logo.getLogoUrl() != null) {
				out.put(logo.getTicker(), logo.getLogoUrl());
			}
		}
		return out;
	}
}
