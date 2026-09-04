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
 * Resilient Alpha Vantage REST client — same rate-limiter + retry shape as {@link FinnhubRest}, but
 * tuned for Alpha Vantage's free-tier budget: a hard 25 calls/day cap (24-hour window) rather than a
 * per-minute one, since Alpha Vantage doesn't publish a per-minute free-tier limit. Used by Agent
 * 10's {@code CandleIngestionService}; unlike Finnhub, Alpha Vantage's daily series endpoint has no
 * date-range parameters — every call returns the same fixed window (compact = ~100 days, full = full
 * history), so ingestion dedups by date after the fact instead of requesting a narrower range.
 */
@Component
public class AlphaVantageRest {

	private static final Logger log = LoggerFactory.getLogger(AlphaVantageRest.class);

	private final RateLimiter rateLimiter;
	private final Retry retry;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public AlphaVantageRest(AlphaVantageResilienceProperties props) {
		this.rateLimiter = RateLimiter.of("alphavantage", RateLimiterConfig.custom()
				.limitForPeriod(props.limitForPeriod())
				.limitRefreshPeriod(Duration.ofSeconds(props.refreshPeriodSeconds()))
				.timeoutDuration(Duration.ofSeconds(props.acquireTimeoutSeconds()))
				.build());
		this.retry = Retry.of("alphavantage", RetryConfig.custom()
				.maxAttempts(props.maxAttempts())
				.intervalFunction(IntervalFunction.ofExponentialBackoff(
						Duration.ofMillis(props.initialBackoffMs()), props.backoffMultiplier()))
				.retryExceptions(AlphaVantageTransientException.class)
				.build());
	}

	/** Rate-limited, retried GET. Returns the body on HTTP 200, or empty if dropped/failed. */
	public Optional<String> get(String url) {
		Supplier<String> decorated = Retry.decorateSupplier(retry,
				RateLimiter.decorateSupplier(rateLimiter, () -> doGet(url)));
		try {
			return Optional.ofNullable(decorated.get());
		} catch (RequestNotPermitted ex) {
			log.warn("Alpha Vantage daily budget exhausted; dropping call");
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("Alpha Vantage call failed: {}", ex.getMessage());
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
				throw new AlphaVantageTransientException("HTTP " + code);
			}
			throw new IllegalStateException("Alpha Vantage HTTP " + code);
		} catch (IOException ex) {
			throw new AlphaVantageTransientException("I/O error: " + ex.getMessage(), ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Alpha Vantage call interrupted", ex);
		}
	}
}
