package com.argus.portfolio;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live portfolio valuation + value-history endpoints (Stories 3.4/3.6, FR-2/FR-4), session-gated
 * under {@code /api/portfolio}. {@code /value} returns the current snapshot (live updates arrive
 * over STOMP {@code /topic/portfolio}); {@code /value-history} returns the chart series. Resource
 * returned directly; camelCase JSON.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioValueController {

	private final LivePortfolioService live;
	private final PortfolioHistoryService history;

	public PortfolioValueController(LivePortfolioService live, PortfolioHistoryService history) {
		this.live = live;
		this.history = history;
	}

	@GetMapping("/value")
	public PortfolioSnapshot value() {
		return live.currentSnapshot();
	}

	@GetMapping("/value-history")
	public List<ValuePoint> valueHistory(@RequestParam(required = false) String range) {
		return history.history(HistoryRange.fromParam(range));
	}
}
