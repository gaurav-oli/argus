package com.argus.intelligence;

import com.argus.portfolio.Position;
import com.argus.portfolio.PositionRepository;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MVP {@link KnownUniverse} = the current portfolio holdings (Story 4.4). When a watchlist feature
 * lands, add a second provider and compose them rather than changing this one.
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
		for (Position p : positions.findAllByOrderByTickerAsc()) {
			if (p.getTicker() != null) {
				known.add(p.getTicker().trim().toUpperCase());
			}
		}
		return known;
	}
}
