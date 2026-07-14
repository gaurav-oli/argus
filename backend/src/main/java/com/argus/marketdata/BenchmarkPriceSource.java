package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Benchmark (SPY) quote for benchmark-relative paper-trade scoring (Fable 5 review item 2). Goes
 * through the resilient {@link FinnhubRest} client (rate-limited, retried) and caches the last good
 * quote briefly so the Investor's open and close passes don't burn the free-tier budget. Key-gated
 * like the rest of Finnhub access: with no API key every call returns empty and callers degrade to
 * absolute-return scoring.
 */
@Component
public class BenchmarkPriceSource {

	private static final Logger log = LoggerFactory.getLogger(BenchmarkPriceSource.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration CACHE_TTL = Duration.ofMinutes(5);

	private final FinnhubRest rest;
	private final String apiKey;
	private final String symbol;

	private volatile CachedQuote cached;

	public BenchmarkPriceSource(FinnhubRest rest,
			@Value("${argus.finnhub.api-key:}") String apiKey,
			@Value("${argus.benchmark.symbol:SPY}") String symbol) {
		this.rest = rest;
		this.apiKey = apiKey;
		this.symbol = symbol;
	}

	/** Latest benchmark price, from the short cache or a fresh quote; empty when unavailable. */
	public Optional<BigDecimal> latest() {
		CachedQuote c = cached;
		if (c != null && c.at().isAfter(Instant.now().minus(CACHE_TTL))) {
			return Optional.of(c.price());
		}
		Optional<BigDecimal> fresh = fetch();
		fresh.ifPresent(p -> cached = new CachedQuote(p, Instant.now()));
		return fresh;
	}

	private Optional<BigDecimal> fetch() {
		if (apiKey == null || apiKey.isBlank()) {
			return Optional.empty();
		}
		return rest.get("https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + apiKey)
				.flatMap(body -> {
					try {
						JsonNode c = JSON.readTree(body).path("c");
						if (!c.isMissingNode() && c.asDouble() > 0) {
							return Optional.of(new BigDecimal(c.asString()));
						}
					} catch (RuntimeException ex) {
						log.debug("Unparseable {} quote: {}", symbol, ex.getMessage());
					}
					return Optional.empty();
				});
	}

	private record CachedQuote(BigDecimal price, Instant at) {
	}
}
