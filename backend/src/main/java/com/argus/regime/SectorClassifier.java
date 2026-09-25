package com.argus.regime;

import com.argus.marketdata.FinnhubRest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps a ticker to its {@link Sector}. A curated table covers the names Argus actually tracks
 * (including the TSX ETFs and small-cap oddities no free API classifies well); anything else falls
 * back to Finnhub's company-profile industry, mapped by keyword, and is cached for the process
 * lifetime. Unknown/failed lookups resolve to {@link Sector#OTHER} (a mild broad-market beta) and are
 * <em>not</em> cached, so a transient failure can heal on the next call.
 */
@Component
public class SectorClassifier {

	private static final Logger log = LoggerFactory.getLogger(SectorClassifier.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Map<String, Sector> CURATED = Map.ofEntries(
			Map.entry("AAPL", Sector.TECHNOLOGY), Map.entry("MSFT", Sector.TECHNOLOGY),
			Map.entry("ORCL", Sector.TECHNOLOGY), Map.entry("PLTR", Sector.TECHNOLOGY),
			Map.entry("SMCI", Sector.TECHNOLOGY),
			Map.entry("NVDA", Sector.SEMICONDUCTORS), Map.entry("AMD", Sector.SEMICONDUCTORS),
			Map.entry("MU", Sector.SEMICONDUCTORS), Map.entry("TSM", Sector.SEMICONDUCTORS),
			Map.entry("MRVL", Sector.SEMICONDUCTORS), Map.entry("INTC", Sector.SEMICONDUCTORS),
			Map.entry("SNDK", Sector.SEMICONDUCTORS), Map.entry("SKHY", Sector.SEMICONDUCTORS),
			Map.entry("CRWV", Sector.AI_INFRA), Map.entry("NBIS", Sector.AI_INFRA),
			Map.entry("IREN", Sector.AI_INFRA),
			Map.entry("GOOG", Sector.COMMUNICATION), Map.entry("GOOGL", Sector.COMMUNICATION),
			Map.entry("META", Sector.COMMUNICATION), Map.entry("DJT", Sector.COMMUNICATION),
			Map.entry("RUM", Sector.COMMUNICATION),
			Map.entry("AMZN", Sector.CONSUMER_DISCRETIONARY), Map.entry("NKE", Sector.CONSUMER_DISCRETIONARY),
			Map.entry("TSLA", Sector.AUTOS_EV), Map.entry("NIO", Sector.AUTOS_EV), Map.entry("QS", Sector.AUTOS_EV),
			Map.entry("WMT", Sector.CONSUMER_STAPLES), Map.entry("DOL", Sector.CONSUMER_STAPLES),
			Map.entry("RKLB", Sector.INDUSTRIALS_DEFENSE), Map.entry("ONDS", Sector.INDUSTRIALS_DEFENSE),
			Map.entry("SPCX", Sector.INDUSTRIALS_DEFENSE),
			Map.entry("ARKK", Sector.BROAD_GROWTH), Map.entry("XQQ", Sector.BROAD_GROWTH),
			Map.entry("VFV", Sector.BROAD_MARKET), Map.entry("SPY", Sector.BROAD_MARKET),
			Map.entry("VDY", Sector.CANADIAN_EQUITY), Map.entry("ZGLD", Sector.GOLD),
			Map.entry("RETO", Sector.MATERIALS));

	private final FinnhubRest finnhub;
	private final String apiKey;
	private final Map<String, Sector> resolved = new ConcurrentHashMap<>();

	public SectorClassifier(FinnhubRest finnhub, @Value("${argus.finnhub.api-key:}") String apiKey) {
		this.finnhub = finnhub;
		this.apiKey = apiKey;
	}

	public Sector sectorOf(String ticker) {
		if (ticker == null) {
			return Sector.OTHER;
		}
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		Sector curated = CURATED.get(t);
		if (curated != null) {
			return curated;
		}
		Sector cached = resolved.get(t);
		if (cached != null) {
			return cached;
		}
		Sector fetched = lookup(t);
		if (fetched != Sector.OTHER) {
			resolved.put(t, fetched);
		}
		return fetched;
	}

	private Sector lookup(String ticker) {
		if (apiKey == null || apiKey.isBlank()) {
			return Sector.OTHER;
		}
		try {
			return finnhub.get("https://finnhub.io/api/v1/stock/profile2?symbol=" + ticker + "&token=" + apiKey)
					.map(body -> fromIndustry(JSON.readTree(body).path("finnhubIndustry").asString("")))
					.orElse(Sector.OTHER);
		}
		catch (RuntimeException ex) {
			log.debug("Sector lookup for {} failed: {}", ticker, ex.getMessage());
			return Sector.OTHER;
		}
	}

	/** Package-visible for tests: Finnhub industry string → sector. */
	static Sector fromIndustry(String industry) {
		String i = industry == null ? "" : industry.toLowerCase(Locale.ROOT);
		if (i.contains("semiconductor")) return Sector.SEMICONDUCTORS;
		// Healthcare before technology: "Biotechnology" and "Health Care Technology" contain "technology".
		if (i.contains("pharma") || i.contains("biotech") || i.contains("health") || i.contains("life sciences")
				|| i.contains("medical")) return Sector.HEALTHCARE;
		if (i.contains("software") || i.contains("technology") || i.contains("it services")
				|| i.contains("hardware") || i.contains("electronic")) return Sector.TECHNOLOGY;
		if (i.contains("media") || i.contains("communication") || i.contains("telecom")
				|| i.contains("entertainment") || i.contains("interactive")) return Sector.COMMUNICATION;
		if (i.contains("auto")) return Sector.AUTOS_EV;
		if (i.contains("retail") || i.contains("consumer") || i.contains("hotel") || i.contains("leisure")
				|| i.contains("apparel") || i.contains("restaurant")) return Sector.CONSUMER_DISCRETIONARY;
		if (i.contains("food") || i.contains("beverage") || i.contains("household") || i.contains("tobacco")
				|| i.contains("staples")) return Sector.CONSUMER_STAPLES;
		if (i.contains("oil") || i.contains("gas") || i.contains("energy") || i.contains("coal")) return Sector.ENERGY;
		if (i.contains("bank") || i.contains("insurance") || i.contains("financial") || i.contains("capital markets")
				|| i.contains("credit")) return Sector.FINANCIALS;
		if (i.contains("aerospace") || i.contains("defense") || i.contains("machinery") || i.contains("industrial")
				|| i.contains("transportation") || i.contains("airline") || i.contains("construction")) {
			return Sector.INDUSTRIALS_DEFENSE;
		}
		if (i.contains("gold")) return Sector.GOLD;
		if (i.contains("metals") || i.contains("mining") || i.contains("chemical") || i.contains("materials")) {
			return Sector.MATERIALS;
		}
		if (i.contains("utilit")) return Sector.UTILITIES;
		if (i.contains("real estate") || i.contains("reit")) return Sector.REAL_ESTATE;
		return Sector.OTHER;
	}
}
