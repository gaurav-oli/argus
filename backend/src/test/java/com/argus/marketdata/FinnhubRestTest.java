package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Resilience behavior of {@link FinnhubRest} (Story 4.5) against a local HTTP server: retry-with-backoff
 * on 429, give up after max attempts, no retry on permanent 4xx, and rate-limit drop when exhausted.
 */
class FinnhubRestTest {

	private HttpServer server;
	private final AtomicInteger requests = new AtomicInteger();
	private volatile IntUnaryOperator statusForRequest; // request# (1-based) -> HTTP status

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			int n = requests.incrementAndGet();
			int status = statusForRequest.applyAsInt(n);
			byte[] body = (status == 200 ? "[]" : "err").getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/news";
	}

	private FinnhubRest rest(FinnhubResilienceProperties props) {
		return new FinnhubRest(props);
	}

	private static FinnhubResilienceProperties props(int limitForPeriod, int acquireTimeoutSeconds,
			int maxAttempts) {
		return new FinnhubResilienceProperties(limitForPeriod, 60, acquireTimeoutSeconds, maxAttempts,
				10, 2.0, "none");
	}

	@Test
	void retriesTransient429ThenSucceeds() {
		statusForRequest = n -> (n == 1) ? 429 : 200; // first 429, then 200
		Optional<String> body = rest(props(100, 5, 3)).get(url());

		assertTrue(body.isPresent(), "should succeed on retry");
		assertEquals("[]", body.get());
		assertEquals(2, requests.get(), "one retry after the 429");
	}

	@Test
	void givesUpAfterMaxAttemptsOnPersistent429() {
		statusForRequest = n -> 429;
		Optional<String> body = rest(props(100, 5, 3)).get(url());

		assertTrue(body.isEmpty(), "exhausted retries return empty, not an exception");
		assertEquals(3, requests.get(), "tried exactly maxAttempts times");
	}

	@Test
	void doesNotRetryPermanent4xx() {
		statusForRequest = n -> 404;
		Optional<String> body = rest(props(100, 5, 3)).get(url());

		assertTrue(body.isEmpty());
		assertEquals(1, requests.get(), "permanent 4xx is not retried");
	}

	@Test
	void rateLimiterDropsCallWhenBudgetExhausted() {
		statusForRequest = n -> 200;
		FinnhubRest rest = rest(props(1, 0, 1)); // 1 call per minute, no wait for a permit

		Optional<String> first = rest.get(url());
		Optional<String> second = rest.get(url());

		assertTrue(first.isPresent(), "first call within budget succeeds");
		assertTrue(second.isEmpty(), "second call is dropped by the rate limiter");
		assertEquals(1, requests.get(), "the dropped call never hits the server");
	}
}
