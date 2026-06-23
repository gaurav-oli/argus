package com.argus.marketdata;

/**
 * A retryable Finnhub failure (Story 4.5) — HTTP 429 (rate limited), 5xx, or a network I/O error.
 * Resilience4j's retry is configured to back off and re-attempt only on this exception; permanent
 * failures (other 4xx) are not retried.
 */
public class FinnhubTransientException extends RuntimeException {

	public FinnhubTransientException(String message) {
		super(message);
	}

	public FinnhubTransientException(String message, Throwable cause) {
		super(message, cause);
	}
}
