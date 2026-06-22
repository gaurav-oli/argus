package com.argus.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historical USD/CAD lookups with a write-through cache (Story 3.2). The cache is keyed by the
 * requested date, so a repeat lookup of the same date never re-hits the upstream source. A lookup
 * that the source can't resolve returns empty (the caller flags the lot {@code fxEstimated}).
 */
@Service
public class FxRateService {

	static final String PAIR = "USDCAD";

	private final FxRateClient client;
	private final FxRateRepository cache;

	public FxRateService(FxRateClient client, FxRateRepository cache) {
		this.client = client;
		this.cache = cache;
	}

	/** USD/CAD on {@code date} (nearest prior business day), from cache or the upstream source. */
	@Transactional
	public Optional<BigDecimal> usdCadOn(LocalDate date) {
		if (date == null) {
			return Optional.empty();
		}
		Optional<FxRate> cached = cache.findById(new FxRate.Key(PAIR, date));
		if (cached.isPresent()) {
			return Optional.of(cached.get().getRate());
		}
		Optional<BigDecimal> fetched = client.usdCadOn(date);
		fetched.ifPresent(rate -> cache.save(new FxRate(PAIR, date, rate, client.sourceName())));
		return fetched;
	}
}
