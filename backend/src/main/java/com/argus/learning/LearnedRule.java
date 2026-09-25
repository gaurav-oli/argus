package com.argus.learning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** A lesson mined from the system's own trades. Only {@link Status#ACTIVE} rules change behaviour. */
@Entity
@Table(name = "learned_rule")
public class LearnedRule {

	public enum Kind { PENALTY, BOOST, BLOCK, CAP_HOLD, SIZE }

	public enum Status { PROPOSED, ACTIVE, RETIRED, REJECTED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Kind kind;

	@Column(nullable = false)
	private String predicates;

	@Column(nullable = false)
	private String description;

	private String explanation;

	@Column(name = "effect_value", nullable = false)
	private BigDecimal effectValue;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PROPOSED;

	@Column(name = "support_trades", nullable = false)
	private int supportTrades;

	@Column(name = "support_clusters", nullable = false)
	private int supportClusters;

	@Column(name = "win_rate")
	private BigDecimal winRate;

	@Column(name = "mean_excess")
	private BigDecimal meanExcess;

	@Column(name = "holdout_clusters")
	private Integer holdoutClusters;

	@Column(name = "holdout_mean_excess")
	private BigDecimal holdoutMeanExcess;

	@Column(name = "verdict_note")
	private String verdictNote;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "activated_at")
	private Instant activatedAt;

	@Column(name = "retired_at")
	private Instant retiredAt;

	@Column(name = "last_evaluated_at")
	private Instant lastEvaluatedAt;

	protected LearnedRule() {
		// JPA
	}

	public LearnedRule(Kind kind, Set<String> predicates, String description, double effectValue) {
		this.kind = kind;
		this.predicates = String.join(",", new java.util.TreeSet<>(predicates));
		this.description = description;
		this.effectValue = BigDecimal.valueOf(effectValue).setScale(3, RoundingMode.HALF_UP);
	}

	/** Record the fit-window and hold-out statistics that justify (or fail to justify) this rule. */
	public void recordEvidence(int trades, int clusters, double winRate, double meanExcess, Integer holdoutClusters, Double holdoutMeanExcess) {
		this.supportTrades = trades;
		this.supportClusters = clusters;
		this.winRate = BigDecimal.valueOf(winRate).setScale(4, RoundingMode.HALF_UP);
		this.meanExcess = BigDecimal.valueOf(meanExcess).setScale(3, RoundingMode.HALF_UP);
		this.holdoutClusters = holdoutClusters;
		this.holdoutMeanExcess = holdoutMeanExcess == null ? null : BigDecimal.valueOf(holdoutMeanExcess).setScale(3, RoundingMode.HALF_UP);
		this.lastEvaluatedAt = Instant.now();
	}

	public void activate(String note) {
		this.status = Status.ACTIVE;
		this.verdictNote = note;
		if (this.activatedAt == null) {
			this.activatedAt = Instant.now();
		}
	}

	public void reject(String note) {
		this.status = Status.REJECTED;
		this.verdictNote = note;
	}

	public void retire(String note) {
		this.status = Status.RETIRED;
		this.verdictNote = note;
		this.retiredAt = Instant.now();
	}

	public void explain(String explanation) {
		this.explanation = explanation;
	}

	public void describe(String description) {
		this.description = description;
	}

	/** The feature tokens that must all be present for this rule to apply. */
	public Set<String> predicateSet() {
		return Arrays.stream(predicates.split(",")).map(String::trim).filter(s -> !s.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	public boolean matches(Set<String> tokens) {
		return tokens.containsAll(predicateSet());
	}

	public Long getId() { return id; }
	public Kind getKind() { return kind; }
	public String getPredicates() { return predicates; }
	public String getDescription() { return description; }
	public String getExplanation() { return explanation; }
	public BigDecimal getEffectValue() { return effectValue; }
	public Status getStatus() { return status; }
	public int getSupportTrades() { return supportTrades; }
	public int getSupportClusters() { return supportClusters; }
	public BigDecimal getWinRate() { return winRate; }
	public BigDecimal getMeanExcess() { return meanExcess; }
	public Integer getHoldoutClusters() { return holdoutClusters; }
	public BigDecimal getHoldoutMeanExcess() { return holdoutMeanExcess; }
	public String getVerdictNote() { return verdictNote; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getActivatedAt() { return activatedAt; }
	public Instant getRetiredAt() { return retiredAt; }
	public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
}
