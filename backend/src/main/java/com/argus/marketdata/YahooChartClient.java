package com.argus.marketdata;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keyless daily-bar client over Yahoo Finance's chart endpoint — the same unofficial endpoint
 * {@link CanadianEtfPriceFeed} already uses for TSX prices. It exists because Agent 10's original
 * sources could not carry the universe: Finnhub's free tier 403s on candles, and Alpha Vantage's
 * free tier is 25 calls/day (against 38 tickers) with {@code outputsize=full} now premium-only, which
 * deadlocked ingestion (every ticker's first call asks for {@code full}, so no history ever landed).
 * One call returns up to a year of daily OHLCV plus the live quote, and it also serves the index and
 * ETF symbols (^VIX, ^TNX, sector SPDRs) the market-regime feed reads.
 *
 * <p>Unofficial means best-effort: every failure (HTTP error, rate limit, unparseable body, unknown
 * symbol) returns {@link Optional#empty()} and callers degrade — never throw.
 */
@Component
public class YahooChartClient {

	private static final Logger log = LoggerFactory.getLogger(YahooChartClient.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** One daily bar; {@code date} is the exchange-local trading date. */
	public record Bar(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
			long volume) {
	}

	/**
	 * A symbol's recent history plus its live quote. {@code livePrice} is the last traded price and
	 * {@code liveDate} the exchange-local date it belongs to (so a still-forming session's bar can be
	 * told apart from completed ones).
	 */
	public record Series(String symbol, String currency, BigDecimal livePrice, LocalDate liveDate,
			List<Bar> bars) {

		/** Close of the last <em>completed</em> session before {@code liveDate}. */
		public Optional<BigDecimal> previousClose() {
			for (int i = bars.size() - 1; i >= 0; i--) {
				if (liveDate == null || bars.get(i).date().isBefore(liveDate)) {
					return Optional.of(bars.get(i).close());
				}
			}
			return Optional.empty();
		}

		/** Live price vs. the last completed close, in percent. */
		public Optional<Double> changePct1d() {
			Optional<BigDecimal> prev = previousClose();
			if (livePrice == null || prev.isEmpty() || prev.get().signum() <= 0) {
				return Optional.empty();
			}
			return Optional.of(pct(livePrice, prev.get()));
		}

		/** Live price vs. the completed close {@code sessionsBack} sessions before the latest one. */
		public Optional<Double> changePct(int sessionsBack) {
			List<Bar> completed = bars.stream().filter(b -> liveDate == null || b.date().isBefore(liveDate)).toList();
			int idx = completed.size() - sessionsBack;
			if (livePrice == null || idx < 0 || idx >= completed.size()
					|| completed.get(idx).close().signum() <= 0) {
				return Optional.empty();
			}
			return Optional.of(pct(livePrice, completed.get(idx).close()));
		}

		private static double pct(BigDecimal now, BigDecimal then) {
			return now.subtract(then).divide(then, 6, java.math.RoundingMode.HALF_UP).doubleValue() * 100.0;
		}
	}

	/** Fetch {@code range} (e.g. "1mo", "1y") of daily bars for {@code symbol}; empty on any failure. */
	public Optional<Series> fetch(String symbol, String range) {
		try {
			String url = BASE + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=" + range
					+ "&interval=1d";
			HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10)).header("User-Agent", "Mozilla/5.0").GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				log.debug("Yahoo chart {} → HTTP {}", symbol, res.statusCode());
				return Optional.empty();
			}
			return parse(symbol, res.body());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception ex) {
			log.debug("Yahoo chart {} failed: {}", symbol, ex.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * The latest trade including pre-market and after-hours — the last 1-minute bar of today's extended session —
	 * where {@link #fetch}'s {@code livePrice} is only the regular session's. Empty on any failure.
	 */
	public Optional<BigDecimal> extendedPrice(String symbol) {
		try {
			String url = BASE + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=1d&interval=1m&includePrePost=true";
			HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10)).header("User-Agent", "Mozilla/5.0").GET().build(),
					HttpResponse.BodyHandlers.ofString());
			return res.statusCode() == 200 ? lastClose(res.body()) : Optional.empty();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception ex) {
			log.debug("Yahoo extended price {} failed: {}", symbol, ex.getMessage());
			return Optional.empty();
		}
	}

	/** Package-visible for tests: the last non-null close in a chart response. */
	static Optional<BigDecimal> lastClose(String body) {
		JsonNode closes = JSON.readTree(body).path("chart").path("result").path(0).path("indicators").path("quote").path(0)
				.path("close");
		for (int i = closes.size() - 1; i >= 0; i--) {
			if (closes.path(i).isNumber() && closes.path(i).asDouble() > 0) {
				return Optional.of(new BigDecimal(closes.path(i).asString()));
			}
		}
		return Optional.empty();
	}

	/** Package-visible for tests: parse a chart response body. */
	static Optional<Series> parse(String symbol, String body) {
		JsonNode result = JSON.readTree(body).path("chart").path("result").path(0);
		if (result.isMissingNode() || result.isNull()) {
			return Optional.empty();
		}
		JsonNode meta = result.path("meta");
		long gmtOffset = meta.path("gmtoffset").asLong(0);
		BigDecimal live = meta.path("regularMarketPrice").isNumber()
				? new BigDecimal(meta.path("regularMarketPrice").asString()) : null;
		LocalDate liveDate = meta.path("regularMarketTime").isNumber()
				? toDate(meta.path("regularMarketTime").asLong(), gmtOffset) : null;

		JsonNode ts = result.path("timestamp");
		JsonNode q = result.path("indicators").path("quote").path(0);
		List<Bar> bars = new ArrayList<>();
		for (int i = 0; i < ts.size(); i++) {
			JsonNode close = q.path("close").path(i);
			JsonNode open = q.path("open").path(i);
			JsonNode high = q.path("high").path(i);
			JsonNode low = q.path("low").path(i);
			if (!close.isNumber() || !open.isNumber() || !high.isNumber() || !low.isNumber()) {
				continue; // Yahoo pads halted/holiday sessions with nulls
			}
			bars.add(new Bar(toDate(ts.path(i).asLong(), gmtOffset), new BigDecimal(open.asString()),
					new BigDecimal(high.asString()), new BigDecimal(low.asString()),
					new BigDecimal(close.asString()), q.path("volume").path(i).asLong(0)));
		}
		if (bars.isEmpty() && live == null) {
			return Optional.empty();
		}
		return Optional.of(new Series(symbol, meta.path("currency").asString("USD"), live, liveDate,
				List.copyOf(bars)));
	}

	private static LocalDate toDate(long epochSeconds, long gmtOffsetSeconds) {
		return Instant.ofEpochSecond(epochSeconds + gmtOffsetSeconds).atZone(ZoneOffset.UTC).toLocalDate();
	}
}
