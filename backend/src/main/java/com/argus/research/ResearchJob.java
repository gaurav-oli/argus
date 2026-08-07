package com.argus.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Agent 9 — an on-demand, user-triggered research pass on one ticker. Unlike every other agent, this
 * one only runs when asked: it plans its own steps, works through them gathering real data from the
 * same sources Agents 1/2/4/8 already collect, can revise its remaining steps mid-run, then
 * synthesizes a report. State is persisted (not the volatile in-memory shape
 * {@code BackupTriggerService} uses for the single-slot backup trigger) because progress must survive
 * a page refresh and multiple tickers can be researched at once, keyed by job id.
 *
 * <p>{@code plan}/{@code findings} are raw JSON text (same convention as {@code TradeDecision.snapshot}
 * — serialized/parsed by the service layer, not a Hibernate JSON type), so this entity itself is a
 * thin persistence shell around whatever {@link com.argus.research.ResearchAgentService} builds.
 */
@Entity
@Table(name = "research_jobs")
public class ResearchJob {

	public enum Status { PLANNING, RESEARCHING, REVISING_PLAN, SYNTHESIZING, DONE, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false, columnDefinition = "text")
	private String plan = "[]";

	@Column(nullable = false, columnDefinition = "text")
	private String findings = "{}";

	@Column(columnDefinition = "text")
	private String report;

	@Column(columnDefinition = "text")
	private String error;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected ResearchJob() {
		// JPA
	}

	public ResearchJob(String ticker) {
		this.ticker = ticker;
		this.status = Status.PLANNING;
	}

	public void applyStatus(Status status) {
		this.status = status;
		this.updatedAt = Instant.now();
	}

	public void applyPlan(String planJson) {
		this.plan = planJson;
		this.updatedAt = Instant.now();
	}

	public void applyFindings(String findingsJson) {
		this.findings = findingsJson;
		this.updatedAt = Instant.now();
	}

	public void complete(String reportMarkdown) {
		this.report = reportMarkdown;
		this.status = Status.DONE;
		this.updatedAt = Instant.now();
	}

	public void fail(String errorMessage) {
		this.error = errorMessage;
		this.status = Status.FAILED;
		this.updatedAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getTicker() {
		return ticker;
	}

	public Status getStatus() {
		return status;
	}

	public String getPlan() {
		return plan;
	}

	public String getFindings() {
		return findings;
	}

	public String getReport() {
		return report;
	}

	public String getError() {
		return error;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
