package com.argus.calendar;

import com.argus.marketdata.FinnhubRest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caches ticker -> company logo URL (Finnhub {@code /stock/profile2}) so the calendar UI can show
 * a company icon per event with no external call in the request path (Ask-AI panel companion
 * feature). Active only when {@code argus.finnhub.api-key} is set, matching
 * {@link FinnhubEarningsSource}. A ticker is fetched at most once — a miss (no logo, or the call
 * failed/was rate-limited) is cached too so ingestion doesn't keep retrying it.
 */
@Service
@ConditionalOnExpression("'${argus.finnhub.api-key:}'.length() > 0")
public class CompanyLogoService {

	private static final Logger log = LoggerFactory.getLogger(CompanyLogoService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final String apiKey;
	private final FinnhubRest finnhub;
	private final CompanyLogoRepository logos;

	public CompanyLogoService(@Value("${argus.finnhub.api-key}") String apiKey, FinnhubRest finnhub,
			CompanyLogoRepository logos) {
		this.apiKey = apiKey;
		this.finnhub = finnhub;
		this.logos = logos;
	}

	/** Fetches and caches any ticker in {@code tickers} not already cached. Best-effort. */
	public void ensureCached(Collection<String> tickers) {
		Set<String> distinct = new HashSet<>();
		for (String t : tickers) {
			if (t != null && !t.isBlank()) {
				distinct.add(t.trim().toUpperCase());
			}
		}
		if (distinct.isEmpty()) {
			return;
		}
		Set<String> alreadyCached = logos.findCachedTickers(distinct);
		for (String ticker : distinct) {
			if (alreadyCached.contains(ticker)) {
				continue;
			}
			try {
				logos.save(new CompanyLogo(ticker, fetchLogoUrl(ticker).orElse(null)));
			} catch (RuntimeException ex) {
				log.warn("Company logo fetch failed for {}: {}", ticker, ex.getMessage());
			}
		}
	}

	private Optional<String> fetchLogoUrl(String ticker) {
		String url = "https://finnhub.io/api/v1/stock/profile2?symbol=" + ticker + "&token=" + apiKey;
		Optional<String> body = finnhub.get(url);
		if (body.isEmpty()) {
			return Optional.empty();
		}
		try {
			JsonNode logo = JSON.readTree(body.get()).path("logo");
			String value = logo.asString("").trim();
			return value.isEmpty() ? Optional.empty() : Optional.of(value);
		} catch (RuntimeException ex) {
			log.warn("Company logo parse failed for {}: {}", ticker, ex.getMessage());
			return Optional.empty();
		}
	}
}
