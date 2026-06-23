package com.argus.marketdata;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finnhub trade-WebSocket price feed (Story 3.4). Only instantiated when {@code argus.finnhub.api-key}
 * is set (the laptop dev free key, or the Mini) — with no key the platform runs without a live feed.
 * Connects to {@code wss://ws.finnhub.io}, subscribes to the held tickers, and forwards each trade
 * to the handler. Failures are logged and never crash the runtime. The real WS round-trip is
 * validated with a key (laptop/Mini); the value engine that consumes it is unit-tested separately.
 */
@Component
@ConditionalOnProperty(name = "argus.finnhub.api-key")
public class FinnhubPriceFeed implements PriceFeed {

	private static final Logger log = LoggerFactory.getLogger(FinnhubPriceFeed.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String apiKey;
	private volatile WebSocket socket;

	public FinnhubPriceFeed(@Value("${argus.finnhub.api-key}") String apiKey) {
		this.apiKey = apiKey;
	}

	@Override
	public void start(Supplier<Collection<String>> symbols, PriceTick handler) {
		try {
			this.socket = HttpClient.newHttpClient().newWebSocketBuilder()
					.buildAsync(URI.create("wss://ws.finnhub.io?token=" + apiKey), new Listener(handler))
					.join();
			for (String symbol : symbols.get()) {
				socket.sendText("{\"type\":\"subscribe\",\"symbol\":\"" + symbol + "\"}", true);
			}
			log.info("Finnhub price feed connected; subscribed to {} symbols", symbols.get().size());
		} catch (RuntimeException ex) {
			log.warn("Finnhub price feed failed to start: {}", ex.getMessage());
		}
	}

	@Override
	public void stop() {
		WebSocket s = this.socket;
		if (s != null) {
			s.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
		}
	}

	/** Accumulates (possibly fragmented) text frames and parses Finnhub trade messages. */
	private final class Listener implements WebSocket.Listener {

		private final PriceTick handler;
		private final StringBuilder buffer = new StringBuilder();

		private Listener(PriceTick handler) {
			this.handler = handler;
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				dispatch(buffer.toString());
				buffer.setLength(0);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			log.warn("Finnhub price feed error: {}", error.getMessage());
		}

		private void dispatch(String message) {
			try {
				JsonNode root = JSON.readTree(message);
				if (!"trade".equals(root.path("type").asString())) {
					return; // ignore ping/subscribe acks
				}
				for (JsonNode t : root.path("data")) {
					String symbol = t.path("s").asString();
					JsonNode price = t.path("p");
					long ts = t.path("t").asLong();
					if (symbol != null && !symbol.isBlank() && !price.isMissingNode()) {
						handler.onTick(symbol, new BigDecimal(price.asString()),
								ts > 0 ? Instant.ofEpochMilli(ts) : Instant.now());
					}
				}
			} catch (RuntimeException ex) {
				log.debug("Dropped unparseable Finnhub frame");
			}
		}
	}
}
