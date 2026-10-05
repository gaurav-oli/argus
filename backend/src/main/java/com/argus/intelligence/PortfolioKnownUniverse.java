package com.argus.intelligence;

import com.argus.portfolio.PositionRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MVP {@link KnownUniverse} = the current portfolio holdings (Story 4.4). When a watchlist feature
 * lands, add a second provider and compose them rather than changing this one.
 *
 * <p>Phase 2 (multi-user): deliberately reads {@link PositionRepository#allTickersAcrossAllUsers()}
 * (a tenant-filter-bypassing native query), not the normal {@code @TenantId}-scoped finder — per the
 * binding privacy rule, everyone's ticker SYMBOLS merge into one shared universe for Agents/
 * Intelligence, even though the amounts behind them stay completely private to each person. This is
 * also what lets Agents keep working as background jobs with no signed-in user on the thread.
 */
@Component
public class PortfolioKnownUniverse implements KnownUniverse {

	private final PositionRepository positions;

	public PortfolioKnownUniverse(PositionRepository positions) {
		this.positions = positions;
	}

	@Override
	public Set<String> knownTickers() {
		Set<String> known = new LinkedHashSet<>();
		for (String ticker : positions.allTickersAcrossAllUsers()) {
			if (ticker != null) {
				known.add(ticker.trim().toUpperCase());
			}
		}
		return known;
	}
}
