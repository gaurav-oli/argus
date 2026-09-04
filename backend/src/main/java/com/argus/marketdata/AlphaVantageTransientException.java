package com.argus.marketdata;

/**
 * A retryable Alpha Vantage failure — HTTP 429/5xx or a network I/O error. Resilience4j's retry is
 * configured to back off and re-attempt only on this exception; permanent failures (other 4xx, or
 * a 200 body without a usable payload) are not retried.
 */
public class AlphaVantageTransientException extends RuntimeException {

	public AlphaVantageTransientException(String message) {
		super(message);
	}

	public AlphaVantageTransientException(String message, Throwable cause) {
		super(message, cause);
	}
}
