package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bank of Canada Valet historical USD/CAD (Story 3.2). Series {@code FXUSDCAD} (CAD per 1 USD) is
 * the rate the CRA accepts for ACB/tax. Free, no API key, no rate limits → laptop-buildable. We
 * query a short window ending on the target date and take the latest observation ≤ the date
 * (nearest prior business day, covering weekends/holidays). Any failure → empty (caller flags
 * estimated); the rate parsing is extracted to {@link #parseLatest} for unit testing without network.
 */
@Component
public class BankOfCanadaFxClient implements FxRateClient {

	private static final String SOURCE = "bankofcanada-valet";
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final RestClient http;

	public BankOfCanadaFxClient(
			@Value("${argus.fx.valet-base-url:https://www.bankofcanada.ca/valet}") String baseUrl) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
		factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
		this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	@Override
	public String sourceName() {
		return SOURCE;
	}

	@Override
	public Optional<BigDecimal> usdCadOn(LocalDate date) {
		if (date == null) {
			return Optional.empty();
		}
		try {
			String body = http.get()
					.uri("/observations/FXUSDCAD/json?start_date={start}&end_date={end}",
							date.minusDays(14), date) // 14d window covers long holiday/closure stacks
					.retrieve()
					.body(String.class);
			return parseLatest(body, date);
		} catch (RuntimeException ex) {
			return Optional.empty(); // network/HTTP/parse failure → unavailable, never throw
		}
	}

	/**
	 * Parse a Valet {@code observations} payload and return the value of the latest observation whose
	 * date is on or before {@code onOrBefore}. Observations arrive in ascending date order. Package
	 * -private + static so it's unit-tested with canned JSON (no network).
	 */
	static Optional<BigDecimal> parseLatest(String body, LocalDate onOrBefore) {
		if (body == null || body.isBlank()) {
			return Optional.empty();
		}
		JsonNode root;
		try {
			root = JSON.readTree(body);
		} catch (RuntimeException ex) {
			return Optional.empty(); // malformed payload → unavailable, never throw
		}
		BigDecimal latest = null;
		for (JsonNode obs : root.path("observations")) {
			String d = obs.path("d").asString();
			JsonNode value = obs.path("FXUSDCAD").path("v");
			if (d == null || d.isBlank() || value.isMissingNode()) {
				continue;
			}
			if (LocalDate.parse(d).isAfter(onOrBefore)) {
				continue; // never use a rate from after the purchase date
			}
			try {
				latest = new BigDecimal(value.asString());
			} catch (NumberFormatException ignored) {
				// skip a malformed observation rather than failing the whole lookup
			}
		}
		return Optional.ofNullable(latest);
	}
}
