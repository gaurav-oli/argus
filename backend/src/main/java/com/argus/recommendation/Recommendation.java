package com.argus.recommendation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A weather-style recommendation produced by Agent 5 (Stories 6.1/6.2). Holds the auditable
 * probabilities + confidence from the scoring engine and the per-agent diagnostic signals. The
 * direction is derived from the probability (not the LLM): bullish when bull ≥ bear.
 */
@Entity
@Table(name = "recommendations")
public class Recommendation {

	private static final int SCALE = 4;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SignalDirection direction;

	@Column(name = "bull_probability", nullable = false)
	private BigDecimal bullProbability;

	@Column(name = "bear_probability", nullable = false)
	private BigDecimal bearProbability;

	@Column(nullable = false)
	private BigDecimal confidence;

	@Column(name = "price_target")
	private BigDecimal priceTarget;

	private String horizon;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RecommendationStatus status = RecommendationStatus.PENDING;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	/** Null only for legacy/verdict-less rows (tests, the dev seeder); the trigger always sets it. */
	@Enumerated(EnumType.STRING)
	private RecommendationAction action;

	@Column(name = "conviction_score")
	private Integer convictionScore;

	@Column(name = "hold_days")
	private Integer holdDays;

	@Column(name = "horizon_label")
	private String horizonLabel;

	private String thesis;

	private String reasons;

	private String caveats;

	@Column(name = "exit_plan")
	private String exitPlan;

	private String sector;

	/** The situation this call was made in, as a JSON array of feature tokens — what the Trade Learner mines by. */
	private String features;

	/** The learned lessons that shaped this call, newline-separated. */
	private String lessons;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
	@JoinColumn(name = "recommendation_id", nullable = false)
	private List<RecommendationSignal> signals = new ArrayList<>();

	protected Recommendation() {
		// JPA
	}

	public Recommendation(String ticker, ProbabilityScore score, List<AgentSignal> signals,
			BigDecimal priceTarget, String horizon) {
		this.ticker = ticker;
		this.bullProbability = scaled(score.bullProbability());
		this.bearProbability = scaled(score.bearProbability());
		this.confidence = scaled(score.confidence());
		this.direction = score.bullProbability() >= 0.5 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		this.priceTarget = priceTarget;
		this.horizon = horizon;
		for (AgentSignal s : signals) {
			this.signals.add(new RecommendationSignal(s));
		}
	}

	/** Attach the policy's decision (action, conviction score, holding period, reasoning). */
	public void applyVerdict(RecommendationPolicy.Verdict verdict, String sector) {
		this.action = verdict.action();
		this.convictionScore = verdict.score();
		this.holdDays = verdict.holdDays();
		this.horizonLabel = verdict.horizonLabel();
		this.thesis = verdict.thesis();
		this.reasons = String.join("\n", verdict.reasons());
		this.caveats = String.join("\n", verdict.caveats());
		this.exitPlan = verdict.exitPlan();
		this.sector = sector;
		this.features = com.argus.learning.FeatureTokens.toJson(verdict.features());
		this.lessons = String.join("\n", verdict.learned());
		if (verdict.action().actionable()) {
			// The call's direction follows the action; a WATCH keeps the engine's lean for the audit trail.
			this.direction = verdict.action().direction();
		}
	}

	public void markStatus(RecommendationStatus newStatus) {
		this.status = newStatus;
	}

	private static BigDecimal scaled(double v) {
		return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public SignalDirection getDirection() {
		return direction;
	}

	public BigDecimal getBullProbability() {
		return bullProbability;
	}

	public BigDecimal getBearProbability() {
		return bearProbability;
	}

	public BigDecimal getConfidence() {
		return confidence;
	}

	public BigDecimal getPriceTarget() {
		return priceTarget;
	}

	public String getHorizon() {
		return horizon;
	}

	public RecommendationStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<RecommendationSignal> getSignals() {
		return signals;
	}

	public RecommendationAction getAction() {
		return action;
	}

	public Integer getConvictionScore() {
		return convictionScore;
	}

	public Integer getHoldDays() {
		return holdDays;
	}

	public String getHorizonLabel() {
		return horizonLabel;
	}

	public String getThesis() {
		return thesis;
	}

	public String getReasons() {
		return reasons;
	}

	public String getCaveats() {
		return caveats;
	}

	public String getExitPlan() {
		return exitPlan;
	}

	public String getSector() {
		return sector;
	}

	public String getFeatures() {
		return features;
	}

	public String getLessons() {
		return lessons;
	}

	/** Whether this is a call worth acting on: an actionable verdict, or a legacy row with no verdict. */
	public boolean isActionable() {
		return action == null || action.actionable();
	}
}
