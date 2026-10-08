package com.argus.technical;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.ListingResolver;
import com.argus.marketdata.YahooChartClient;
import com.argus.portfolio.LivePortfolioService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The best current price for any ticker Argus tracks — not just held ones. The streaming feed only
 * covers holdings (every tick re-pushes everyone's portfolio, so it is kept small); watchlist and
 * discovered names had no intraday price at all, so their support/resistance stayed measured from
 * yesterday's close all day. This polls Yahoo every few minutes, around US market hours, for the
 * tracked tickers the feed doesn't price. Lookups never fetch inline, so a page load stays fast.
 */
@Service
public class LivePriceService {

	private static final Logger log = LoggerFactory.getLogger(LivePriceService.class);
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
	/** Pre-market through the after-close window before tonight's candles land. */
	private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
	private static final LocalTime WINDOW_END = LocalTime.of(17, 0);
	/** A polled quote older than this is not offered as "live". */
	static final Duration MAX_AGE = Duration.ofHours(24);

	private record Quote(double price, Instant at) {
	}

	private final LivePortfolioService feed;
	private final YahooChartClient yahoo;
	private final ListingResolver listings;
	private final KnownUniverse universe;
	private final Map<String, Quote> quotes = new ConcurrentHashMap<>();

	/** Off in tests so no scheduled poll makes outbound HTTP from a test JVM. */
	@Value("${argus.live-quotes.enabled:true}")
	private boolean enabled = true;

	public LivePriceService(LivePortfolioService feed, YahooChartClient yahoo, ListingResolver listings,
			KnownUniverse universe) {
		this.feed = feed;
		this.yahoo = yahoo;
		this.listings = listings;
		this.universe = universe;
	}

	/** The streaming price if the feed has one, else the latest polled quote, else empty. */
	public Optional<Double> livePrice(String ticker) {
		if (ticker == null) {
			return Optional.empty();
		}
		String t = ticker.trim().toUpperCase(java.util.Locale.ROOT);
		Optional<Double> streamed = feed.latestPrice(t).map(BigDecimal::doubleValue);
		if (streamed.isPresent()) {
			return streamed;
		}
		Quote q = quotes.get(t);
		return q != null && q.at().isAfter(Instant.now().minus(MAX_AGE)) ? Optional.of(q.price()) : Optional.empty();
	}

	/** {@link #livePrice} as the feed's exact decimal when streamed — for code that books prices (the paper investor). */
	public Optional<BigDecimal> latestPrice(String ticker) {
		if (ticker == null) {
			return Optional.empty();
		}
		Optional<BigDecimal> streamed = feed.latestPrice(ticker.trim().toUpperCase(java.util.Locale.ROOT));
		return streamed.isPresent() ? streamed : livePrice(ticker).map(BigDecimal::valueOf);
	}

	@Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
	public void refresh() {
		if (!enabled || (!quotes.isEmpty() && !inWindow(Instant.now()))) {
			return; // outside the window there is nothing new to poll, except to fill a cold cache once
		}
		int polled = 0;
		for (String ticker : universe.knownTickers()) {
			if (feed.latestPrice(ticker).isPresent()) {
				continue;
			}
			try {
				for (String symbol : listings.yahooSymbols(ticker, false)) {
					Optional<BigDecimal> price = yahoo.fetch(symbol, "5d").map(YahooChartClient.Series::livePrice);
					if (price.isPresent() && price.get().signum() > 0) {
						quotes.put(ticker, new Quote(price.get().doubleValue(), Instant.now()));
						polled++;
						break;
					}
				}
			}
			catch (RuntimeException ex) {
				log.debug("Live quote for {} failed: {}", ticker, ex.getMessage());
			}
		}
		log.debug("Live quotes refreshed for {} unstreamed ticker(s)", polled);
	}

	static boolean inWindow(Instant at) {
		ZonedDateTime ny = at.atZone(NEW_YORK);
		DayOfWeek day = ny.getDayOfWeek();
		LocalTime time = ny.toLocalTime();
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
				&& !time.isBefore(WINDOW_START) && time.isBefore(WINDOW_END);
	}
}
