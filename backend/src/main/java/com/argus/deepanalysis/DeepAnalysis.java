package com.argus.deepanalysis;

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
import java.time.Duration;
import java.time.Instant;

/** One Agent 11 analysis run — its progress while running and, once done, the verdict and full reasoning. */
@Entity
@Table(name = "deep_analysis")
public class DeepAnalysis {

	public enum Status { QUEUED, RUNNING, DONE, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.QUEUED;

	@Column(name = "trigger_source", nullable = false)
	private String triggerSource;

	private String stage;

	@Enumerated(EnumType.STRING)
	private DeepVerdict verdict;

	@Column(name = "hold_days")
	private Integer holdDays;

	private Integer conviction;
	private String headline;
	private String thesis;

	@Column(name = "bull_case")
	private String bullCase;

	@Column(name = "bear_case")
	private String bearCase;

	private String risks;
	private String catalysts;
	private String invalidation;

	@Column(name = "technical_summary")
	private String technicalSummary;

	@Column(name = "fundamental_summary")
	private String fundamentalSummary;

	@Column(name = "catalyst_summary")
	private String catalystSummary;

	@Column(name = "macro_summary")
	private String macroSummary;

	@Column(name = "skeptic_view")
	private String skepticView;

	@Column(name = "guard_notes")
	private String guardNotes;

	@Column(name = "technical_score")
	private BigDecimal technicalScore;

	@Column(name = "fundamental_score")
	private BigDecimal fundamentalScore;

	@Column(name = "consensus_score")
	private BigDecimal consensusScore;

	private String stages;
	private String evidence;
	private String model;
	private String error;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	/** The price whose breach would prove the verdict wrong (below the price for a buy), validated against the chart. */
	@Column(name = "invalidation_price")
	private BigDecimal invalidationPrice;

	/** The price when the analysis ran — the scorecard's entry point. */
	@Column(name = "price_at_analysis")
	private BigDecimal priceAtAnalysis;

	/** INTACT, or AT_RISK once the thesis tracker sees new information that undermines the verdict. */
	@Column(name = "thesis_status", nullable = false)
	private String thesisStatus = "INTACT";

	@Column(name = "thesis_reason")
	private String thesisReason;

	@Column(name = "thesis_checked_at")
	private Instant thesisCheckedAt;

	protected DeepAnalysis() {
		// JPA
	}

	public DeepAnalysis(String ticker, String triggerSource) {
		this.ticker = ticker;
		this.triggerSource = triggerSource;
	}

	// ---- lifecycle ----

	public void markRunning() {
		this.status = Status.RUNNING;
		this.startedAt = Instant.now();
		this.stage = "Starting";
	}

	public void setStage(String stage) {
		this.stage = stage;
	}

	public void recordEvidence(String evidence) {
		this.evidence = evidence;
	}

	public void recordScores(Double technical, Double fundamental, Double consensus) {
		this.technicalScore = scaled(technical);
		this.fundamentalScore = scaled(fundamental);
		this.consensusScore = scaled(consensus);
	}

	public void recordSpecialists(String technical, String fundamental, String catalyst, String macro, String skeptic) {
		this.technicalSummary = technical;
		this.fundamentalSummary = fundamental;
		this.catalystSummary = catalyst;
		this.macroSummary = macro;
		this.skepticView = skeptic;
	}

	public void recordStages(String stagesJson, String model) {
		this.stages = stagesJson;
		this.model = model;
	}

	/** Finish with the (already guard-checked) verdict. {@code ttl} is how long the verdict stays fresh for the recommender. */
	public void complete(DeepVerdict verdict, Integer holdDays, int conviction, String headline, String thesis,
			String bullCase, String bearCase, String risks, String catalysts, String invalidation, String guardNotes,
			Duration ttl) {
		this.verdict = verdict;
		this.holdDays = holdDays;
		this.conviction = conviction;
		this.headline = headline;
		this.thesis = thesis;
		this.bullCase = bullCase;
		this.bearCase = bearCase;
		this.risks = risks;
		this.catalysts = catalysts;
		this.invalidation = invalidation;
		this.guardNotes = guardNotes;
		this.status = Status.DONE;
		this.stage = "Done";
		this.finishedAt = Instant.now();
		this.expiresAt = this.finishedAt.plus(ttl);
	}

	/** Record the entry price and the level that would invalidate the verdict. */
	public void recordEntry(Double priceAtAnalysis, Double invalidationPrice) {
		this.priceAtAnalysis = priceAtAnalysis == null ? null : BigDecimal.valueOf(priceAtAnalysis).setScale(6, RoundingMode.HALF_UP);
		this.invalidationPrice = invalidationPrice == null ? null : BigDecimal.valueOf(invalidationPrice).setScale(6, RoundingMode.HALF_UP);
	}

	/** The thesis tracker saw something that undermines this verdict. */
	public void flagAtRisk(String reason) {
		this.thesisStatus = "AT_RISK";
		this.thesisReason = reason;
		this.thesisCheckedAt = Instant.now();
	}

	public void markChecked() {
		this.thesisCheckedAt = Instant.now();
	}

	public boolean isAtRisk() {
		return "AT_RISK".equals(thesisStatus);
	}

	public void fail(String message) {
		this.status = Status.FAILED;
		this.stage = "Failed";
		this.error = message;
		this.finishedAt = Instant.now();
	}

	private static BigDecimal scaled(Double v) {
		return v == null ? null : BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
	}

	// ---- accessors ----

	public Long getId() { return id; }
	public String getTicker() { return ticker; }
	public Status getStatus() { return status; }
	public String getTriggerSource() { return triggerSource; }
	public String getStage() { return stage; }
	public DeepVerdict getVerdict() { return verdict; }
	public Integer getHoldDays() { return holdDays; }
	public Integer getConviction() { return conviction; }
	public String getHeadline() { return headline; }
	public String getThesis() { return thesis; }
	public String getBullCase() { return bullCase; }
	public String getBearCase() { return bearCase; }
	public String getRisks() { return risks; }
	public String getCatalysts() { return catalysts; }
	public String getInvalidation() { return invalidation; }
	public String getTechnicalSummary() { return technicalSummary; }
	public String getFundamentalSummary() { return fundamentalSummary; }
	public String getCatalystSummary() { return catalystSummary; }
	public String getMacroSummary() { return macroSummary; }
	public String getSkepticView() { return skepticView; }
	public String getGuardNotes() { return guardNotes; }
	public BigDecimal getTechnicalScore() { return technicalScore; }
	public BigDecimal getFundamentalScore() { return fundamentalScore; }
	public BigDecimal getConsensusScore() { return consensusScore; }
	public String getStages() { return stages; }
	public String getEvidence() { return evidence; }
	public String getModel() { return model; }
	public String getError() { return error; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getFinishedAt() { return finishedAt; }
	public Instant getExpiresAt() { return expiresAt; }
	public BigDecimal getInvalidationPrice() { return invalidationPrice; }
	public BigDecimal getPriceAtAnalysis() { return priceAtAnalysis; }
	public String getThesisStatus() { return thesisStatus; }
	public String getThesisReason() { return thesisReason; }
	public Instant getThesisCheckedAt() { return thesisCheckedAt; }
}
