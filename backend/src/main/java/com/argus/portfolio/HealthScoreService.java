package com.argus.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio Health Score (Story 3.8, FR-6) — a deterministic, auditable rule/weight engine.
 * <b>Never an LLM</b> (Argus framing rule: scores are model-derived, not language-model-generated).
 * Starts at 100 and deducts for concentration (single-name + top-3), thin diversification, and
 * unconfirmed data; agent-sentiment / risk-alert inputs are explicit no-ops until those epics exist.
 * Weighting uses CAD ACB (always available) so the score is deterministic without live prices.
 */
@Service
public class HealthScoreService {

	private static final Logger log = LoggerFactory.getLogger(HealthScoreService.class);
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	private final PositionRepository positions;
	private final HealthScoreRepository scores;
	private final ObjectMapper json = JsonMapper.builder().build();

	public HealthScoreService(PositionRepository positions, HealthScoreRepository scores) {
		this.positions = positions;
		this.scores = scores;
	}

	/** Compute the current score + explained deductions. Empty portfolio → 100 (nothing at risk). */
	@Transactional(readOnly = true)
	public HealthScoreResult compute() {
		List<Position> ps = positions.findAllByOrderByTickerAsc();
		List<HealthDeduction> deductions = new ArrayList<>();
		if (ps.isEmpty()) {
			return new HealthScoreResult(100, List.of(), Instant.now());
		}

		concentration(ps, deductions);
		diversification(ps, deductions);
		dataQuality(ps, deductions);
		// agentSentiment / openRiskAlerts / pendingActions → stubbed (Epics 4/6/8).

		int sum = deductions.stream().mapToInt(HealthDeduction::points).sum();
		int score = Math.max(0, Math.min(100, 100 - sum));
		return new HealthScoreResult(score, List.copyOf(deductions), Instant.now());
	}

	private void concentration(List<Position> ps, List<HealthDeduction> out) {
		double total = ps.stream().map(Position::getCadAcb).filter(Objects::nonNull)
				.mapToDouble(BigDecimal::doubleValue).filter(v -> v > 0).sum();
		if (total <= 0) {
			return; // no priced cost basis → can't weight (e.g. all FX-estimated)
		}
		record Weight(String ticker, double w) {
		}
		List<Weight> weights = ps.stream()
				.filter(p -> p.getCadAcb() != null && p.getCadAcb().signum() > 0)
				.map(p -> new Weight(p.getTicker(), p.getCadAcb().doubleValue() / total))
				.sorted((a, b) -> Double.compare(b.w(), a.w()))
				.toList();

		Weight top = weights.get(0);
		if (top.w() > 0.25) {
			int pts = Math.min(20, (int) Math.round((top.w() - 0.25) * 100));
			if (pts > 0) {
				out.add(new HealthDeduction("concentration_single", "Single-name concentration", pts,
						top.ticker() + " is " + pct(top.w()) + " of your portfolio",
						"Trim " + top.ticker() + " or add other holdings"));
			}
		}
		double top3 = weights.stream().limit(3).mapToDouble(Weight::w).sum();
		if (top3 > 0.60) {
			int pts = Math.min(20, (int) Math.round((top3 - 0.60) * 100));
			if (pts > 0) {
				out.add(new HealthDeduction("concentration_top3", "Top-heavy portfolio", pts,
						"Top 3 holdings are " + pct(top3) + " of your portfolio",
						"Diversify beyond your largest names"));
			}
		}
	}

	private void diversification(List<Position> ps, List<HealthDeduction> out) {
		int n = ps.size();
		if (n < 5) {
			out.add(new HealthDeduction("diversification", "Few holdings", (5 - n) * 4,
					"Only " + n + " holding" + (n == 1 ? "" : "s"),
					"Add more positions to spread risk"));
		}
	}

	private void dataQuality(List<Position> ps, List<HealthDeduction> out) {
		long flagged = ps.stream().filter(p -> p.isNeedsReview() || p.isFxEstimated()).count();
		if (flagged > 0) {
			out.add(new HealthDeduction("data_quality", "Unconfirmed data", (int) Math.min(10, flagged * 2),
					flagged + " position" + (flagged == 1 ? "" : "s") + " need review or FX confirmation",
					"Confirm purchase FX / review flagged holdings"));
		}
	}

	/** Upsert today's score point for the trend (idempotent). */
	@Transactional
	public void capture() {
		HealthScoreResult result = compute();
		String breakdown = json.writeValueAsString(result.deductions());
		LocalDate today = LocalDate.now(TORONTO);
		scores.findByScoredOn(today)
				.ifPresentOrElse(s -> {
					s.update(result.score(), breakdown);
					scores.save(s);
				}, () -> scores.save(new HealthScore(today, result.score(), breakdown)));
	}

	/** The daily score series for the last {@code days} (clamped 1–365), ascending (Story 3.9). */
	@Transactional(readOnly = true)
	public List<HealthPoint> history(int days) {
		int window = Math.max(1, Math.min(365, days));
		LocalDate from = LocalDate.now(TORONTO).minusDays(window);
		return scores.findByScoredOnGreaterThanEqualOrderByScoredOnAsc(from).stream()
				.map(s -> new HealthPoint(s.getScoredOn(), s.getScore()))
				.toList();
	}

	/** Daily recompute (06:00 ET — after overnight agent runs land in later epics). */
	@Scheduled(cron = "0 0 6 * * *", zone = "America/New_York")
	public void scheduledCapture() {
		try {
			capture();
		} catch (RuntimeException ex) {
			log.warn("Scheduled health-score capture failed: {}", ex.getMessage());
		}
	}

	private static String pct(double weight) {
		return Math.round(weight * 100) + "%";
	}
}
