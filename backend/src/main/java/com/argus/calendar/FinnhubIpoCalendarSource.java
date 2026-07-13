package com.argus.calendar;

import com.argus.calendar.CalendarSource.RawEvent;
import com.argus.marketdata.FinnhubRest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finnhub upcoming-IPO source for Agent 7. Unlike the earnings source this is <em>market-wide</em> —
 * it ignores {@code heldTickers} and pulls every upcoming initial public offering in the look-ahead
 * window from {@code /calendar/ipo} (one rate-limited call per run, no per-symbol fan-out). So the
 * calendar surfaces companies before they list — even ones you don't hold and can't yet, which is the
 * whole point of "upcoming IPOs". Active only when {@code argus.finnhub.api-key} is set; calls go
 * through {@link FinnhubRest} (rate limit + retry) so an approached limit degrades instead of breaking.
 *
 * <p>Filtering keeps the signal high: only {@code expected}/{@code priced} listings are surfaced
 * (withdrawn/early-filing ones are dropped), and deals below {@code argus.calendar.ipo-min-value-usd}
 * are skipped <em>when the size is known</em> — an IPO that hasn't priced yet (unknown size) is always
 * kept so nothing genuinely upcoming is hidden.
 */
@Component
@ConditionalOnExpression("'${argus.finnhub.api-key:}'.length() > 0")
public class FinnhubIpoCalendarSource implements CalendarSource {

	static final String NAME = "finnhub-ipo";

	private static final Logger log = LoggerFactory.getLogger(FinnhubIpoCalendarSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String apiKey;
	private final int lookaheadDays;
	private final boolean enabled;
	private final long minValueUsd;
	private final FinnhubRest finnhub;

	public FinnhubIpoCalendarSource(@Value("${argus.finnhub.api-key}") String apiKey,
			CalendarProperties props, FinnhubRest finnhub) {
		this.apiKey = apiKey;
		this.lookaheadDays = props.lookaheadDays();
		this.enabled = props.ipoEnabled();
		this.minValueUsd = props.ipoMinValueUsd();
		this.finnhub = finnhub;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawEvent> fetch(Collection<String> heldTickers) {
		if (!enabled) {
			return List.of();
		}
		LocalDate from = LocalDate.now();
		LocalDate to = from.plusDays(lookaheadDays);
		String url = "https://finnhub.io/api/v1/calendar/ipo?from=" + from + "&to=" + to + "&token=" + apiKey;
		Optional<String> body = finnhub.get(url);
		if (body.isEmpty()) {
			return List.of();
		}
		try {
			JsonNode rows = JSON.readTree(body.get()).path("ipoCalendar");
			List<RawEvent> out = new ArrayList<>();
			for (JsonNode n : rows) {
				toEvent(n).ifPresent(out::add);
			}
			log.info("Finnhub IPO calendar: {} upcoming IPO(s) after filtering", out.size());
			return out;
		}
		catch (RuntimeException ex) {
			log.warn("Finnhub IPO calendar parse failed: {}", ex.getMessage());
			return List.of();
		}
	}

	private Optional<RawEvent> toEvent(JsonNode n) {
		String date = n.path("date").asString("").trim();
		String status = n.path("status").asString("").trim().toLowerCase();
		if (date.isEmpty() || !(status.equals("expected") || status.equals("priced"))) {
			return Optional.empty();
		}
		long value = n.path("totalSharesValue").asLong(0);
		if (minValueUsd > 0 && value > 0 && value < minValueUsd) {
			return Optional.empty(); // known-small deal — skip; unknown size (0) still passes through
		}

		String symbol = n.path("symbol").asString("").trim();
		String name = n.path("name").asString("").trim();
		String exchange = n.path("exchange").asString("").trim();
		String price = n.path("price").asString("").trim();

		String company = name.isEmpty() ? symbol : name;
		if (company.isEmpty()) {
			return Optional.empty(); // nothing to show
		}
		LocalDate eventDate;
		try {
			eventDate = LocalDate.parse(date);
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}

		String ticker = symbol.isEmpty() ? null : symbol;
		String externalId = "IPO:" + (symbol.isEmpty() ? company.replaceAll("\\s+", "-") : symbol) + ":" + date;
		return Optional.of(new RawEvent(CalendarEventType.IPO, ticker, title(company, exchange, price),
				eventDate, NAME, externalId));
	}

	private static String title(String company, String exchange, String price) {
		StringBuilder details = new StringBuilder();
		if (!exchange.isEmpty()) {
			details.append(exchange);
		}
		if (!price.isEmpty()) {
			details.append(details.length() > 0 ? ", " : "").append('$').append(price);
		}
		String suffix = details.length() > 0 ? " (" + details + ")" : "";
		return company + " IPO" + suffix;
	}
}
