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
 * Finnhub earnings-calendar source for Agent 7 (Story 5.1, FR-21). One rate-limited REST call per
 * held ticker against {@code /calendar/earnings}, scoped to the look-ahead window. Active only when
 * {@code argus.finnhub.api-key} is set; calls go through {@link FinnhubRest} (rate limit + retry),
 * so an approached Finnhub limit degrades the run instead of breaking it.
 */
@Component
@ConditionalOnExpression("'${argus.finnhub.api-key:}'.length() > 0")
public class FinnhubEarningsSource implements CalendarSource {

	static final String NAME = "finnhub-earnings";

	private static final Logger log = LoggerFactory.getLogger(FinnhubEarningsSource.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String apiKey;
	private final int lookaheadDays;
	private final int lookbackDays;
	private final FinnhubRest finnhub;

	public FinnhubEarningsSource(@Value("${argus.finnhub.api-key}") String apiKey,
			CalendarProperties props, FinnhubRest finnhub) {
		this.apiKey = apiKey;
		this.lookaheadDays = props.lookaheadDays();
		this.lookbackDays = props.earningsLookbackDays();
		this.finnhub = finnhub;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public List<RawEvent> fetch(Collection<String> heldTickers) {
		if (heldTickers == null || heldTickers.isEmpty()) {
			return List.of();
		}
		LocalDate today = LocalDate.now();
		// Re-covers the recent past too (not just today() → today()+lookahead) so a date that was
		// ingested before it reported gets revisited once Finnhub has actual/estimate EPS for it —
		// see Agent7CalendarService#store, which updates the existing row rather than skipping it.
		LocalDate from = today.minusDays(lookbackDays);
		LocalDate to = today.plusDays(lookaheadDays);
		List<RawEvent> out = new ArrayList<>();
		for (String ticker : heldTickers) {
			out.addAll(fetchTicker(ticker, from, to));
		}
		return out;
	}

	private List<RawEvent> fetchTicker(String ticker, LocalDate from, LocalDate to) {
		String url = "https://finnhub.io/api/v1/calendar/earnings?from=" + from + "&to=" + to
				+ "&symbol=" + ticker + "&token=" + apiKey;
		Optional<String> body = finnhub.get(url);
		if (body.isEmpty()) {
			return List.of();
		}
		try {
			JsonNode rows = JSON.readTree(body.get()).path("earningsCalendar");
			List<RawEvent> out = new ArrayList<>();
			for (JsonNode n : rows) {
				String date = n.path("date").asString("").trim();
				String symbol = n.path("symbol").asString(ticker).trim();
				if (date.isEmpty()) {
					continue;
				}
				LocalDate eventDate = LocalDate.parse(date);
				Double epsActual = nullableDouble(n, "epsActual");
				Double epsEstimate = nullableDouble(n, "epsEstimate");
				Double epsSurprisePercent = (epsActual != null && epsEstimate != null && epsEstimate != 0)
						? (epsActual - epsEstimate) / Math.abs(epsEstimate) * 100
						: null;
				out.add(new RawEvent(CalendarEventType.EARNINGS, symbol, symbol + " earnings",
						eventDate, NAME, "EARNINGS:" + symbol + ":" + date,
						epsActual, epsEstimate, epsSurprisePercent));
			}
			return out;
		} catch (RuntimeException ex) {
			log.warn("Finnhub earnings parse failed for {}: {}", ticker, ex.getMessage());
			return List.of();
		}
	}

	/** Finnhub returns JSON null (not a missing field) for EPS values not yet reported. */
	private static Double nullableDouble(JsonNode row, String field) {
		JsonNode v = row.path(field);
		return (v.isMissingNode() || v.isNull()) ? null : v.asDouble();
	}
}
