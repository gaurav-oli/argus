package com.argus.technical;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.AlphaVantageRest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 10's price-history ingestion. Daily, after the US close, it pulls each held ticker's OHLC
 * candles from Alpha Vantage ({@code TIME_SERIES_DAILY}) via the shared rate-limited
 * {@link AlphaVantageRest} client, dedups against what's stored, and persists new rows.
 *
 * <p>Originally built against Finnhub's {@code /stock/candle}, which turned out to 403 on the free
 * tier (never actually verified before the pipeline was built around it — see the technical debt
 * writeup). Alpha Vantage's daily series has no date-range parameters: every call returns the same
 * fixed window (either the latest ~100 trading days, or full multi-decade history), so there's no
 * separate "backfill vs. incremental" URL to build — only which window size to request. A ticker's
 * <b>first-ever</b> ingest requests {@code outputsize=full} and keeps only the most recent
 * {@code argus.technical.backfill-days}, so {@link TechnicalIndicators} has enough history
 * immediately rather than waiting weeks of incremental polling. Subsequent ingests request
 * {@code outputsize=compact} (~100 days) — comfortably more overlap than Finnhub's old 5-day window,
 * still cheap since Alpha Vantage's free tier costs the same 1 call regardless of window size.
 *
 * <p>The free tier's hard 25-calls/day budget is tight against ~20+ held tickers (one call each,
 * daily) — see {@link AlphaVantageRest}'s rate limiter, which drops calls once exhausted rather than
 * blocking or throwing, same degrade-gracefully contract as the old Finnhub client.
 */
@Component
@ConditionalOnExpression("'${argus.alpha-vantage.api-key:}'.length() > 0")
public class CandleIngestionService {

	private static final Logger log = LoggerFactory.getLogger(CandleIngestionService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final String TIME_SERIES_KEY = "Time Series (Daily)";

	private final String apiKey;
	private final AlphaVantageRest alphaVantage;
	private final PriceCandleRepository candles;
	private final KnownUniverse universe;
	private final TechnicalAnalysisProperties props;

	public CandleIngestionService(@Value("${argus.alpha-vantage.api-key}") String apiKey,
			AlphaVantageRest alphaVantage, PriceCandleRepository candles, KnownUniverse universe,
			TechnicalAnalysisProperties props) {
		this.apiKey = apiKey;
		this.alphaVantage = alphaVantage;
		this.candles = candles;
		this.universe = universe;
		this.props = props;
	}

	@Scheduled(cron = "${argus.technical.ingest-cron:0 30 16 * * *}", zone = "America/New_York")
	public void scheduledTick() {
		try {
			ingestOnce();
		}
		catch (RuntimeException ex) {
			log.warn("Candle ingestion cycle failed: {}", ex.getMessage());
		}
	}

	/** Public so tests (and a future manual trigger) can drive one cycle deterministically. Per-ticker
	 * try/catch (matches {@code NewsIngestionService}'s convention, not {@code SecIngestionService}'s
	 * whole-cycle catch) — a bad symbol or a rate-limit drop on one ticker must not skip every other
	 * ticker's backfill in the same cycle. */
	public int ingestOnce() {
		List<String> heldTickers = universe.knownTickers().stream().distinct().toList();
		if (heldTickers.isEmpty()) {
			return 0;
		}
		int saved = 0;
		for (String ticker : heldTickers) {
			try {
				saved += ingestTicker(ticker);
			}
			catch (RuntimeException ex) {
				log.warn("Candle ingestion for {} failed: {}", ticker, ex.getMessage());
			}
		}
		if (saved > 0) {
			log.info("Candle ingestion: {} new candle(s) across {} ticker(s)", saved, heldTickers.size());
		}
		return saved;
	}

	private int ingestTicker(String ticker) {
		boolean hasHistory = candles.existsByTicker(ticker);
		String outputSize = hasHistory ? "compact" : "full";
		String url = "https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=" + ticker
				+ "&outputsize=" + outputSize + "&apikey=" + apiKey;
		Optional<String> body = alphaVantage.get(url);
		if (body.isEmpty()) {
			return 0;
		}
		LocalDate oldestWanted = LocalDate.now().minusDays(props.backfillDays());
		return parseAndSave(ticker, body.get(), oldestWanted);
	}

	private int parseAndSave(String ticker, String body, LocalDate oldestWanted) {
		JsonNode root;
		try {
			root = JSON.readTree(body);
		}
		catch (RuntimeException ex) {
			log.warn("Candle response parse failed for {}: {}", ticker, ex.getMessage());
			return 0;
		}
		JsonNode series = root.path(TIME_SERIES_KEY);
		if (!series.isObject()) {
			// Bad symbol, or a soft rate-limit/error notice — Alpha Vantage returns these as HTTP 200
			// bodies with an "Information"/"Note"/"Error Message" key instead of the series. Not an
			// exception (matches the old Finnhub "no_data" convention): log and move on.
			log.warn("No candle series for {}: {}", ticker, root.toString());
			return 0;
		}
		int saved = 0;
		for (Map.Entry<String, JsonNode> entry : series.properties()) {
			LocalDate date = LocalDate.parse(entry.getKey());
			if (date.isBefore(oldestWanted) || candles.existsByTickerAndCandleDate(ticker, date)) {
				continue;
			}
			JsonNode day = entry.getValue();
			candles.save(new PriceCandle(ticker, date,
					new BigDecimal(day.path("1. open").asString()),
					new BigDecimal(day.path("2. high").asString()),
					new BigDecimal(day.path("3. low").asString()),
					new BigDecimal(day.path("4. close").asString()),
					Long.valueOf(day.path("5. volume").asString())));
			saved++;
		}
		return saved;
	}
}
