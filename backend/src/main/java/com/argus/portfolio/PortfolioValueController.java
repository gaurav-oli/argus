package com.argus.portfolio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live portfolio valuation endpoint (Story 3.4, FR-2), session-gated under {@code /api/portfolio}.
 * Returns the current snapshot for initial render; subsequent updates arrive over STOMP
 * ({@code /topic/portfolio}). Resource returned directly; camelCase JSON.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioValueController {

	private final LivePortfolioService live;

	public PortfolioValueController(LivePortfolioService live) {
		this.live = live;
	}

	@GetMapping("/value")
	public PortfolioSnapshot value() {
		return live.currentSnapshot();
	}
}
