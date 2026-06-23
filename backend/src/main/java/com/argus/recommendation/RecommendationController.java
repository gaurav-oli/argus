package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent 5 recommendation endpoints for the Probability Forecast Card UI (Story 6.3), session-gated
 * under {@code /api/recommendations}. Returns the auditable probabilities/confidence, the per-agent
 * diagnostic, the graduation badge, and the Black-Swan confidence cap; and accepts the user's
 * Taken/Declined decision (Story 6.7).
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

	/** When a Black Swan is active, confidence is capped (FR-13). No Black-Swan source exists yet (Epic 10). */
	private static final BigDecimal BLACK_SWAN_CONFIDENCE_CAP = new BigDecimal("0.60");

	private final RecommendationService recommendations;
	private final TradeConfirmationService confirmation;
	private final GraduationService graduation;

	public RecommendationController(RecommendationService recommendations,
			TradeConfirmationService confirmation, GraduationService graduation) {
		this.recommendations = recommendations;
		this.confirmation = confirmation;
		this.graduation = graduation;
	}

	@GetMapping
	public List<RecommendationCard> list() {
		boolean blackSwan = isBlackSwanActive();
		GraduationState state = graduation.currentState();
		return recommendations.recent().stream().map(r -> RecommendationCard.from(r, state, blackSwan)).toList();
	}

	@GetMapping("/{id}")
	public RecommendationCard get(@PathVariable Long id) {
		return recommendations.diagnostic(id)
				.map(r -> RecommendationCard.from(r, graduation.currentState(), isBlackSwanActive()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@PostMapping("/{id}/decision")
	public void decide(@PathVariable Long id, @RequestBody DecisionBody body) {
		confirmation.confirm(id, body.decision(), body.reasoning());
	}

	/** Black Swan state is not modeled yet (Epic 10) — always false; the cap is wired for when it is. */
	private boolean isBlackSwanActive() {
		return false;
	}

	public record DecisionBody(Decision decision, String reasoning) {
	}

	/** The weather-style card: direction, probabilities, confidence (Black-Swan-capped), badge, signals. */
	public record RecommendationCard(Long id, String ticker, String direction, BigDecimal bullProbability,
			BigDecimal bearProbability, BigDecimal confidence, boolean confidenceCapped, BigDecimal priceTarget,
			String horizon, String status, String badge, boolean blackSwanActive, Instant createdAt,
			List<SignalView> signals) {

		static RecommendationCard from(Recommendation r, GraduationState state, boolean blackSwan) {
			BigDecimal confidence = r.getConfidence();
			boolean capped = blackSwan && confidence.compareTo(BLACK_SWAN_CONFIDENCE_CAP) > 0;
			if (capped) {
				confidence = BLACK_SWAN_CONFIDENCE_CAP;
			}
			return new RecommendationCard(r.getId(), r.getTicker(), r.getDirection().name(),
					r.getBullProbability(), r.getBearProbability(), confidence, capped, r.getPriceTarget(),
					r.getHorizon(), r.getStatus().name(), state.badge(), blackSwan, r.getCreatedAt(),
					r.getSignals().stream().map(SignalView::from).toList());
		}
	}

	/** One agent's diagnostic row for the card's signal dots + expandable breakdown. */
	public record SignalView(String agent, String direction, BigDecimal weight, String rationale) {

		static SignalView from(RecommendationSignal s) {
			return new SignalView(s.getAgent(), s.getDirection().name(), s.getWeight(), s.getRationale());
		}
	}
}
