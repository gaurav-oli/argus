package com.argus.recommendation;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analyst Logic Review endpoints (session-gated under {@code /api/ops/logic-review}). {@code POST /run}
 * triggers a review on demand (the model proposes, the backtest decides); {@code GET /last} returns the
 * most recent run for the Ops UI. The review otherwise runs automatically after the nightly tuning.
 */
@RestController
@RequestMapping("/api/ops/logic-review")
public class LogicReviewController {

	private final LogicReviewService review;
	private final JdbcTemplate jdbc;

	public LogicReviewController(LogicReviewService review, JdbcTemplate jdbc) {
		this.review = review;
		this.jdbc = jdbc;
	}

	@PostMapping("/run")
	public LogicReviewService.Result run() {
		return review.review();
	}

	@GetMapping("/last")
	public LastReview last() {
		return jdbc.query(
				"select ran_at, model, sample_size, before_brier, after_brier, before_accuracy, after_accuracy,"
						+ " adopted, reason, proposals::text as proposals from logic_review order by ran_at desc limit 1",
				rs -> rs.next()
						? new LastReview(rs.getTimestamp("ran_at").toInstant(), rs.getString("model"),
								rs.getInt("sample_size"), (Double) rs.getObject("before_brier"),
								(Double) rs.getObject("after_brier"), (Double) rs.getObject("before_accuracy"),
								(Double) rs.getObject("after_accuracy"), rs.getBoolean("adopted"),
								rs.getString("reason"), rs.getString("proposals"))
						: null);
	}

	/** {@code proposals} is the raw JSON array text; the UI parses it. */
	public record LastReview(Instant ranAt, String model, int sampleSize, Double beforeBrier, Double afterBrier,
			Double beforeAccuracy, Double afterAccuracy, boolean adopted, String reason, String proposals) {
	}
}
