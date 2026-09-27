package com.argus.fundamentals;

import com.argus.intelligence.KnownUniverse;
import com.argus.marketdata.FinnhubRest;
import com.argus.regime.MarketRegimeService;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 12 — fundamentals. Fetches a company's profile, ratios, quarterly statements, earnings history,
 * analyst ratings and peer valuations from Finnhub (all available on the existing free key), runs them
 * through {@link FundamentalsAnalyzer}, and stores the result so readers never touch the network.
 *
 * <ul>
 *   <li>{@link #latest} — a cheap database read, for the recommender's hot path.</li>
 *   <li>{@link #getOrRefresh} — fetches when the stored snapshot is older than a bound; used by Agent 11.</li>
 *   <li>A nightly refresh of the whole universe, plus a one-off fill at boot when nothing is stored yet.</li>
 * </ul>
 * A failed or rate-limited fetch is never stored — a transient outage must not overwrite good data (or
 * mark a real company "not applicable"); only a positive "no company data" answer is stored for ETFs.
 * Roughly 10 Finnhub calls per ticker, paced well inside the 60/min limit.
 */
@Service
public class FundamentalsService {

	private static final Logger log = LoggerFactory.getLogger(FundamentalsService.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String BASE = "https://finnhub.io/api/v1/";
	private static final int MAX_PEERS = 5;
	private static final Duration REFRESH_AFTER = Duration.ofHours(20);

	private final FinnhubRest finnhub;
	private final String apiKey;
	private final FundamentalsSnapshotRepository snapshots;
	private final KnownUniverse universe;
	private final ChartStudyService charts;
	private final MarketRegimeService regimes;
	/** One refresh per ticker at a time: a refresh is ~10 Finnhub calls, so a concurrent duplicate is pure waste. */
	private final java.util.concurrent.ConcurrentHashMap<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

	public FundamentalsService(FinnhubRest finnhub, @Value("${argus.finnhub.api-key:}") String apiKey,
			FundamentalsSnapshotRepository snapshots, KnownUniverse universe, ChartStudyService charts, MarketRegimeService regimes) {
		this.finnhub = finnhub;
		this.apiKey = apiKey;
		this.snapshots = snapshots;
		this.universe = universe;
		this.charts = charts;
		this.regimes = regimes;
	}

	/** The stored snapshot regardless of age (empty if never fetched). */
	public Optional<Fundamentals> latest(String ticker) {
		return snapshots.findById(normalize(ticker)).flatMap(FundamentalsService::read);
	}

	/** The stored snapshot only if it is no older than {@code maxAge}. */
	public Optional<Fundamentals> latestFresh(String ticker, Duration maxAge) {
		return snapshots.findById(normalize(ticker))
				.filter(s -> s.getFetchedAt().isAfter(Instant.now().minus(maxAge))).flatMap(FundamentalsService::read);
	}

	/** Stored snapshot if fresh enough, else a live refresh; empty only if neither is available. */
	public Optional<Fundamentals> getOrRefresh(String ticker, Duration maxAge) {
		Optional<Fundamentals> fresh = latestFresh(ticker, maxAge);
		return fresh.isPresent() ? fresh : refresh(ticker).or(() -> latest(ticker));
	}

	/**
	 * Fetch, analyse and store. Empty when Finnhub is unavailable (no key, rate-limited, or the two core
	 * calls both failed) — in which case nothing is stored.
	 */
	public Optional<Fundamentals> refresh(String rawTicker) {
		String ticker = normalize(rawTicker);
		if (apiKey == null || apiKey.isBlank()) {
			return Optional.empty();
		}
		synchronized (locks.computeIfAbsent(ticker, k -> new Object())) {
			// Someone else may have refreshed it while we waited for the lock — use that rather than repeating ~10 calls.
			Optional<Fundamentals> justRefreshed = latestFresh(ticker, Duration.ofSeconds(90));
			if (justRefreshed.isPresent()) {
				return justRefreshed;
			}
			return refreshLocked(ticker);
		}
	}

	private Optional<Fundamentals> refreshLocked(String ticker) {
		Optional<JsonNode> profile = fetch("stock/profile2?symbol=" + ticker);
		Optional<JsonNode> metricBody = fetch("stock/metric?symbol=" + ticker + "&metric=all");
		if (profile.isEmpty() && metricBody.isEmpty()) {
			log.debug("Fundamentals for {}: Finnhub unavailable — keeping any stored snapshot", ticker);
			return Optional.empty();
		}
		JsonNode metric = metricBody.map(b -> b.path("metric")).orElse(JSON.createObjectNode());
		JsonNode prof = profile.orElse(JSON.createObjectNode());
		Fundamentals f;
		if (!hasCompanyData(prof, metric)) {
			f = Fundamentals.notApplicable(ticker, "No company data — likely an ETF/fund or an unrecognised symbol.");
		}
		else {
			JsonNode financials = fetch("stock/financials-reported?symbol=" + ticker + "&freq=quarterly").orElse(null);
			JsonNode earnings = fetch("stock/earnings?symbol=" + ticker).orElse(null);
			JsonNode recs = fetch("stock/recommendation?symbol=" + ticker).orElse(null);
			List<FundamentalsAnalyzer.PeerMetric> peerMetrics = new ArrayList<>();
			peers(ticker, peerMetrics);
			// The reverse DCF needs today's price and the risk-free rate; either may be unknown (then no valuation view).
			Double price = charts.studyFor(ticker).map(ChartStudy::lastClose).orElse(null);
			Double riskFree = regimes.tenYearYieldPct().orElse(null);
			f = FundamentalsAnalyzer.analyze(ticker, prof, metric, financials, earnings, recs, peerMetrics, price, riskFree);
		}
		store(f);
		return Optional.of(f);
	}

	private void peers(String ticker, List<FundamentalsAnalyzer.PeerMetric> out) {
		try {
			Optional<String> body = finnhub.get(BASE + "stock/peers?symbol=" + ticker + "&token=" + apiKey);
			if (body.isEmpty()) return;
			for (JsonNode p : JSON.readTree(body.get())) {
				String sym = p.asString("");
				if (sym.isBlank() || sym.equalsIgnoreCase(ticker) || out.size() >= MAX_PEERS) continue;
				fetch("stock/metric?symbol=" + sym + "&metric=all")
						.ifPresent(b -> out.add(new FundamentalsAnalyzer.PeerMetric(sym, b.path("metric"))));
			}
		}
		catch (RuntimeException ex) {
			log.debug("Peer lookup for {} failed: {}", ticker, ex.getMessage());
		}
	}

	/** Every stored snapshot (companies and ETFs), for the Intelligence page's fundamentals view. */
	public List<Fundamentals> all() {
		List<Fundamentals> out = new ArrayList<>();
		snapshots.findAll().forEach(s -> read(s).ifPresent(out::add));
		return out;
	}

	private Optional<JsonNode> fetch(String path) {
		try {
			return finnhub.get(BASE + path + "&token=" + apiKey).map(JSON::readTree);
		}
		catch (RuntimeException ex) {
			log.debug("Finnhub {} unparseable: {}", path, ex.getMessage());
			return Optional.empty();
		}
	}

	private static boolean hasCompanyData(JsonNode profile, JsonNode metric) {
		boolean p = profile != null && (!profile.path("name").asString("").isBlank() || profile.path("marketCapitalization").isNumber());
		boolean m = metric != null && metric.properties().iterator().hasNext();
		return p || m;
	}

	private void store(Fundamentals f) {
		try {
			snapshots.upsert(f.ticker(), JSON.writeValueAsString(f), f.applicable(), f.score(), f.bias(), f.fetchedAt());
		}
		catch (RuntimeException ex) {
			log.warn("Could not store fundamentals for {}: {}", f.ticker(), ex.getMessage());
		}
	}

	private static Optional<Fundamentals> read(FundamentalsSnapshot s) {
		try {
			return Optional.of(JSON.readValue(s.getPayload(), Fundamentals.class));
		}
		catch (RuntimeException ex) {
			log.warn("Stored fundamentals for {} unreadable: {}", s.getTicker(), ex.getMessage());
			return Optional.empty();
		}
	}

	private static String normalize(String ticker) {
		return ticker == null ? "" : ticker.trim().toUpperCase(java.util.Locale.ROOT);
	}

	// ---- scheduled refresh ----

	@Scheduled(cron = "${argus.fundamentals.refresh-cron:0 0 2 * * *}", zone = "America/New_York")
	public void refreshUniverse() {
		int done = 0;
		for (String t : universe.knownTickers()) {
			try {
				if (latestFresh(t, REFRESH_AFTER).isPresent()) continue;
				refresh(t);
				done++;
				Thread.sleep(1_000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException ex) {
				log.warn("Fundamentals refresh for {} failed: {}", t, ex.getMessage());
			}
		}
		log.info("Fundamentals refresh: {} ticker(s) updated ({})", done, LocalDate.now());
	}

	/** Boot fill — without it a fresh deploy would wait until 02:00 ET for its first fundamentals. */
	@EventListener(ApplicationReadyEvent.class)
	public void fillOnStartup() {
		Thread.startVirtualThread(() -> {
			try {
				if (snapshots.count() == 0 && !universe.knownTickers().isEmpty()) {
					refreshUniverse();
				}
			}
			catch (RuntimeException ex) {
				log.warn("Fundamentals startup fill failed: {}", ex.getMessage());
			}
		});
	}
}
