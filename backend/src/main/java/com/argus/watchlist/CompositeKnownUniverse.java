package com.argus.watchlist;

import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.PortfolioKnownUniverse;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The effective known universe = portfolio holdings ∪ active watchlist tickers. Marked {@link Primary}
 * so every {@link KnownUniverse} consumer (Stranger Danger, and — once rewired — news/social/SEC/calendar
 * ingestion and the recommendation review) sees the widened set. This is the composition the
 * {@link PortfolioKnownUniverse} javadoc anticipated for a future watchlist provider.
 */
@Component
@Primary
public class CompositeKnownUniverse implements KnownUniverse {

	private final PortfolioKnownUniverse portfolio;
	private final WatchlistRepository watchlist;

	public CompositeKnownUniverse(PortfolioKnownUniverse portfolio, WatchlistRepository watchlist) {
		this.portfolio = portfolio;
		this.watchlist = watchlist;
	}

	@Override
	public Set<String> knownTickers() {
		Set<String> all = new LinkedHashSet<>(portfolio.knownTickers());
		for (String ticker : watchlist.activeTickers(Instant.now())) {
			if (ticker != null && !ticker.isBlank()) {
				all.add(ticker.trim().toUpperCase());
			}
		}
		return all;
	}
}
