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
	private final HealthScoreService healthScore;

	public PortfolioValueController(LivePortfolioService live, PortfolioHistoryService history,
			HealthScoreService healthScore) {
		this.live = live;
		this.history = history;
		this.healthScore = healthScore;
	}

	@GetMapping("/value")
	public PortfolioSnapshot value() {
		return live.currentSnapshot();
	}

	@GetMapping("/value-history")
	public List<ValuePoint> valueHistory(@RequestParam(required = false) String range) {
		return history.history(HistoryRange.fromParam(range));
	}

	@GetMapping("/health-score")
	public HealthScoreResult healthScore() {
		return healthScore.compute();
	}

	@GetMapping("/health-score/history")
	public List<HealthPoint> healthScoreHistory(@RequestParam(required = false, defaultValue = "30") int days) {
		return healthScore.history(days);
	}
}
