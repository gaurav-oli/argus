package com.argus.strategy;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Maintains the cross-sectional ranking universe (the S&amp;P 500 by default), with each member's GICS sector —
 * which is also what makes industry momentum computable.
 *
 * <p><b>Known limitation: survivorship bias.</b> This is today's index membership applied to history, so companies
 * that were dropped or went bust are absent, and any backtest over it is flattered. The honest mitigations are to
 * say so (here, and on every backtest view), and to treat the universe as a <em>ranking</em> yardstick for names
 * Argus actually follows rather than as a tradable portfolio whose absolute return means anything. Fixing it
 * properly needs point-in-time constituent data, which is paid.
 */
@Service
public class StrategyUniverseService {

	private static final Logger log = LoggerFactory.getLogger(StrategyUniverseService.class);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final StrategyUniverseRepository repository;
	private final String listUrl;
	private final boolean enabled;

	public StrategyUniverseService(StrategyUniverseRepository repository,
			@Value("${argus.strategy.universe-url:https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv}")
			String listUrl, @Value("${argus.strategy.enabled:true}") boolean enabled) {
		this.repository = repository;
		this.listUrl = listUrl;
		this.enabled = enabled;
	}

	/** Monthly: index membership changes a couple of dozen times a year. */
	@Scheduled(cron = "${argus.strategy.universe-cron:0 30 4 1 * *}", zone = "America/Toronto")
	public void scheduled() {
		if (!enabled) {
			return;
		}
		try {
			refresh();
		}
		catch (RuntimeException ex) {
			log.warn("Agent 15: ranking universe refresh failed: {}", ex.getMessage());
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!enabled) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				if (repository.count() == 0) {
					log.info("Agent 15: ranking universe seeded with {} members", refresh());
				}
			}
			catch (RuntimeException ex) {
				log.warn("Agent 15: ranking universe seeding failed: {}", ex.getMessage());
			}
		});
	}

	/**
	 * Fetch the constituent list and upsert it; members that have left the index are marked inactive rather than
	 * deleted (their stored history stays valid for a backtest that ran while they were in). Returns the active
	 * count, or 0 when the source was unreachable — a failed refresh never empties the universe.
	 */
	public int refresh() {
		Optional<String> body = fetch();
		if (body.isEmpty()) {
			log.warn("Agent 15: constituent list unavailable at {} — keeping the universe as-is", listUrl);
			return (int) repository.countByActiveTrue();
		}
		Map<String, Row> rows = parse(body.get());
		if (rows.isEmpty()) {
			return (int) repository.countByActiveTrue();
		}
		Set<String> seen = new HashSet<>();
		for (Row r : rows.values()) {
			StrategyUniverse m = repository.findById(r.ticker())
					.orElseGet(() -> new StrategyUniverse(r.ticker(), r.name(), r.sector(), r.subIndustry(), "sp500"));
			m.refresh(r.name(), r.sector(), r.subIndustry());
			repository.save(m);
			seen.add(r.ticker());
		}
		for (StrategyUniverse existing : repository.findByActiveTrue()) {
			if (!seen.contains(existing.getTicker())) {
				existing.deactivate();
				repository.save(existing);
			}
		}
		int active = (int) repository.countByActiveTrue();
		log.info("Agent 15: ranking universe refreshed — {} active members", active);
		return active;
	}

	public List<String> activeTickers() {
		return repository.findByActiveTrue().stream().map(StrategyUniverse::getTicker).sorted().toList();
	}

	/** Ticker → GICS sector, for the industry-momentum aggregate. */
	public Map<String, String> sectorsByTicker() {
		Map<String, String> out = new LinkedHashMap<>();
		for (StrategyUniverse m : repository.findByActiveTrue()) {
			if (m.getGicsSector() != null) {
				out.put(m.getTicker(), m.getGicsSector());
			}
		}
		return out;
	}

	record Row(String ticker, String name, String sector, String subIndustry) {
	}

	/** Parse the constituents CSV. Package-visible so the parser is testable without the network. */
	static Map<String, Row> parse(String csv) {
		List<List<String>> table = Csv.parse(new StringReader(csv));
		if (table.isEmpty()) {
			return Map.of();
		}
		List<String> header = table.get(0);
		int iSymbol = indexOf(header, "Symbol");
		int iName = indexOf(header, "Security");
		int iSector = indexOf(header, "GICS Sector");
		int iSub = indexOf(header, "GICS Sub-Industry");
		if (iSymbol < 0) {
			return Map.of();
		}
		Map<String, Row> out = new LinkedHashMap<>();
		for (int i = 1; i < table.size(); i++) {
			List<String> r = table.get(i);
			if (iSymbol >= r.size()) {
				continue;
			}
			String raw = r.get(iSymbol);
			if (raw == null || raw.isBlank()) {
				continue;
			}
			// Yahoo writes class shares with a dash (BRK.B → BRK-B); normalise on the way in.
			String ticker = raw.trim().toUpperCase(Locale.ROOT).replace('.', '-');
			out.put(ticker, new Row(ticker, cell(r, iName), cell(r, iSector), cell(r, iSub)));
		}
		return out;
	}

	private static int indexOf(List<String> header, String name) {
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i) != null && header.get(i).trim().equalsIgnoreCase(name)) {
				return i;
			}
		}
		return -1;
	}

	private static String cell(List<String> row, int i) {
		return i < 0 || i >= row.size() || row.get(i) == null || row.get(i).isBlank() ? null : row.get(i).trim();
	}

	private Optional<String> fetch() {
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(listUrl))
					.timeout(Duration.ofSeconds(30))
					.header("User-Agent", "Argus/1.0 (personal research)")
					.GET().build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			return res.statusCode() == 200 && res.body() != null && !res.body().isBlank()
					? Optional.of(res.body()) : Optional.empty();
		}
		catch (IOException ex) {
			return Optional.empty();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	/** New ArrayList each call so callers can sort/mutate freely. */
	public List<StrategyUniverse> activeMembers() {
		return new ArrayList<>(repository.findByActiveTrue());
	}
}
