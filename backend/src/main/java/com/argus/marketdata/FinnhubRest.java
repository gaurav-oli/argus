package com.argus.marketdata;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resilient Finnhub REST client (Story 4.5, GAP-4). Every GET is governed by a Resilience4j rate
 * limiter (default 60 calls/minute — the free-tier cap) and retried with exponential backoff on
 * transient failures (HTTP 429/5xx, network I/O). When the rate budget is exhausted or retries are
 * exhausted the call is dropped (returns empty) rather than throwing, so a Finnhub limit degrades
 * ingestion to the free sources instead of breaking it. All Finnhub REST access goes through here.
 */
@Component
public class FinnhubRest {

	private static final Logger log = LoggerFactory.getLogger(FinnhubRest.class);

	private final RateLimiter rateLimiter;
	private final Retry retry;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public FinnhubRest(FinnhubResilienceProperties props) {
		this.rateLimiter = RateLimiter.of("finnhub", RateLimiterConfig.custom()
				.limitForPeriod(props.limitForPeriod())
				.limitRefreshPeriod(Duration.ofSeconds(props.refreshPeriodSeconds()))
				.timeoutDuration(Duration.ofSeconds(props.acquireTimeoutSeconds()))
				.build());
		this.retry = Retry.of("finnhub", RetryConfig.custom()
				.maxAttempts(props.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialBackoff(
						Duration.ofMillis(props.initialBackoffMs()), props.backoffMultiplier()))
				.retryExceptions(FinnhubTransientException.class)
				.build());
	}

	/** Rate-limited, retried GET. Returns the body on HTTP 200, or empty if dropped/failed. */
	public Optional<String> get(String url) {
		Supplier<String> decorated = Retry.decorateSupplier(retry,
				RateLimiter.decorateSupplier(rateLimiter, () -> doGet(url)));
		try {
			return Optional.ofNullable(decorated.get());
		} catch (RequestNotPermitted ex) {
			log.warn("Finnhub rate budget exhausted; dropping call");
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("Finnhub call failed: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	private String doGet(String url) {
		try {
			HttpResponse<String> res = http.send(
					HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			int code = res.statusCode();
			if (code == 200) {
				return res.body();
			}
			if (code == 429 || code >= 500) {
				throw new FinnhubTransientException("HTTP " + code);
			}
			// Other 4xx are permanent (bad symbol, auth) — not retried, surfaced as a drop.
			throw new IllegalStateException("Finnhub HTTP " + code);
		} catch (IOException ex) {
			throw new FinnhubTransientException("I/O error: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			// Not retryable: an interrupt means abort now (shutdown), don't back off and re-attempt.
			throw new IllegalStateException("Finnhub call interrupted", ex);
		}
	}
}
