package com.argus.marketdata;

import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Supplemental price source for Toronto-listed securities (e.g. VFV, VDY, XQQ), which Finnhub's free
 * tier doesn't cover — leaving them valued at $0. On a cadence it takes the held tickers Finnhub
 * left unpriced and tries Yahoo Finance with a {@code .TO} suffix; a real TSX symbol returns a CAD
 * price (recorded via the live valuation), while a non-Canadian ticker (e.g. IDEXQ.TO) returns no
 * data and is simply left unpriced. Free, no key; best-effort.
 */
@Component
public class CanadianEtfPriceFeed {

	private static final Logger log = LoggerFactory.getLogger(CanadianEtfPriceFeed.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final LivePortfolioService live;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	public CanadianEtfPriceFeed(LivePortfolioService live) {
		this.live = live;
	}

	@Scheduled(fixedDelayString = "${argus.tsx.poll-ms:300000}",
			initialDelayString = "${argus.tsx.initial-delay-ms:35000}")
	public void poll() {
		for (String ticker : live.unpricedHeldTickers()) {
			try {
				fetchAndRecord(ticker);
			}
			catch (RuntimeException | InterruptedException ex) {
				log.debug("TSX price fetch failed for {}: {}", ticker, ex.getMessage());
				if (ex instanceof InterruptedException) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	private void fetchAndRecord(String ticker) throws InterruptedException {
		String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + ticker
				+ ".TO?interval=1d&range=1d";
		HttpResponse<String> res;
		try {
			res = http.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
					.header("User-Agent", "Mozilla/5.0 (Argus)").GET().build(),
					HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
		if (res.statusCode() != 200) {
			return; // 404 = not a TSX symbol; leave it unpriced
		}
		JsonNode meta = JSON.readTree(res.body()).path("chart").path("result").path(0).path("meta");
		BigDecimal price = decimal(meta.path("regularMarketPrice"));
		if (price == null || price.signum() <= 0) {
			return;
		}
		BigDecimal prevClose = decimal(meta.path("previousClose"));
		if (prevClose == null) {
			prevClose = decimal(meta.path("chartPreviousClose"));
		}
		if (prevClose != null) {
			live.recordPreviousClose(ticker, prevClose);
		}
		live.onPriceTick(ticker, price, Instant.now(), "CAD"); // CAD quote; also re-pushes the snapshot
		log.info("TSX price: {} = {} CAD (Yahoo)", ticker, price);
	}

	private static BigDecimal decimal(JsonNode node) {
		return node != null && node.isNumber() ? node.decimalValue() : null;
	}
}
