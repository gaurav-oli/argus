package com.argus.regime;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The market-regime strip on the Intelligence page — session-gated under {@code /api/market}. */
@RestController
@RequestMapping("/api/market")
public class RegimeController {

	private final MarketRegimeService regimes;

	public RegimeController(MarketRegimeService regimes) {
		this.regimes = regimes;
	}

	@GetMapping("/regime")
	public RegimeView regime() {
		MarketRegime r = regimes.current();
		List<SectorMove> sectors = java.util.Arrays.stream(Sector.values())
				.filter(s -> s != Sector.OTHER && r.sector1d().containsKey(s.benchmark()))
				.map(s -> new SectorMove(s.label(), r.sector1d().get(s.benchmark())))
				.toList();
		return new RegimeView(r.label(), r.summary(), r.spy1d(), r.vix(), r.ratesRising(), sectors);
	}

	public record SectorMove(String sector, double changePct) {
	}

	public record RegimeView(String state, String summary, Double spy1dPct, Double vix, boolean ratesRising,
			List<SectorMove> sectors) {
	}
}
