package com.argus.technical;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.FinnhubRest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
 * candles from Finnhub ({@code /stock/candle}, unused elsewhere in the app) via the shared
 * rate-limited {@link FinnhubRest} client, dedups against what's stored, and persists new rows.
 *
 * <p>Unlike every other ingestion agent, a ticker's <b>first-ever</b> ingest fetches
 * {@code argus.technical.backfill-days} of history in one call — {@link TechnicalIndicators} needs
 * ~60 days of candles before RSI/drawdown are meaningful, and waiting that many days of incremental
 * daily polling would leave the signal silent for two months on every newly held/watched ticker.
 * Subsequent ingests are a small 5-day overlap window (catches any days Finnhub hadn't settled yet
 * on the previous run), not a full re-backfill.
 */
@Component
@ConditionalOnExpression("'${argus.finnhub.api-key:}'.length() > 0")
public class CandleIngestionService {

	private static final Logger log = LoggerFactory.getLogger(CandleIngestionService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final int INCREMENTAL_OVERLAP_DAYS = 5;

	private final String apiKey;
	private final FinnhubRest finnhub;
	private final PriceCandleRepository candles;
	private final KnownUniverse universe;
	private final TechnicalAnalysisProperties props;

	public CandleIngestionService(@Value("${argus.finnhub.api-key}") String apiKey, FinnhubRest finnhub,
			PriceCandleRepository candles, KnownUniverse universe, TechnicalAnalysisProperties props) {
		this.apiKey = apiKey;
		this.finnhub = finnhub;
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
		LocalDate to = LocalDate.now();
		LocalDate from = hasHistory ? to.minusDays(INCREMENTAL_OVERLAP_DAYS) : to.minusDays(props.backfillDays());
		String url = "https://finnhub.io/api/v1/stock/candle?symbol=" + ticker + "&resolution=D&from="
				+ epochSeconds(from) + "&to=" + epochSeconds(to.plusDays(1)) + "&token=" + apiKey;
		Optional<String> body = finnhub.get(url);
		if (body.isEmpty()) {
			return 0;
		}
		return parseAndSave(ticker, body.get());
	}

	private int parseAndSave(String ticker, String body) {
		JsonNode root;
		try {
			root = JSON.readTree(body);
		}
		catch (RuntimeException ex) {
			log.warn("Candle response parse failed for {}: {}", ticker, ex.getMessage());
			return 0;
		}
		if (!"ok".equals(root.path("s").asString(""))) {
			return 0; // "no_data" (holiday-only window, delisted symbol, etc.) — not an error
		}
		JsonNode t = root.path("t");
		JsonNode o = root.path("o");
		JsonNode h = root.path("h");
		JsonNode l = root.path("l");
		JsonNode c = root.path("c");
		JsonNode v = root.path("v");
		int saved = 0;
		for (int i = 0; i < t.size(); i++) {
			LocalDate date = Instant.ofEpochSecond(t.get(i).asLong()).atZone(ZoneOffset.UTC).toLocalDate();
			if (candles.existsByTickerAndCandleDate(ticker, date)) {
				continue;
			}
			candles.save(new PriceCandle(ticker, date, BigDecimal.valueOf(o.get(i).asDouble()),
					BigDecimal.valueOf(h.get(i).asDouble()), BigDecimal.valueOf(l.get(i).asDouble()),
					BigDecimal.valueOf(c.get(i).asDouble()), v.get(i).asLong()));
			saved++;
		}
		return saved;
	}

	private static long epochSeconds(LocalDate date) {
		return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
	}
}
