package com.argus.filings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** One digested SEC filing: the verified structured extraction plus the score code computed from it. */
@Entity
@Table(name = "filing_digest")
public class FilingDigest {

	public enum Kind { EARNINGS_RELEASE, QUARTERLY_REPORT, ANNUAL_REPORT }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String ticker;

	@Column(nullable = false, unique = true)
	private String accession;

	@Column(nullable = false)
	private String form;

	@Column(nullable = false)
	private String kind;

	@Column(name = "filed_at", nullable = false)
	private LocalDate filedAt;

	private String summary;
	private String guidance;

	@Column(name = "guidance_detail")
	private String guidanceDetail;

	private String tone;

	@Column(nullable = false)
	private BigDecimal score;

	@Column(nullable = false)
	private String payload;

	@Column(name = "verified_facts", nullable = false)
	private int verifiedFacts;

	@Column(name = "dropped_facts", nullable = false)
	private int droppedFacts;

	@Column(name = "source_chars", nullable = false)
	private int sourceChars;

	private String model;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected FilingDigest() {
		// JPA
	}

	public FilingDigest(String ticker, String accession, String form, Kind kind, LocalDate filedAt, String summary, String guidance,
			String guidanceDetail, String tone, double score, String payload, int verifiedFacts, int droppedFacts, int sourceChars, String model) {
		this.ticker = ticker;
		this.accession = accession;
		this.form = form;
		this.kind = kind.name();
		this.filedAt = filedAt;
		this.summary = summary;
		this.guidance = guidance;
		this.guidanceDetail = guidanceDetail;
		this.tone = tone;
		this.score = BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP);
		this.payload = payload;
		this.verifiedFacts = verifiedFacts;
		this.droppedFacts = droppedFacts;
		this.sourceChars = sourceChars;
		this.model = model;
	}

	public Long getId() { return id; }
	public String getTicker() { return ticker; }
	public String getAccession() { return accession; }
	public String getForm() { return form; }
	public Kind getKind() { return Kind.valueOf(kind); }
	public LocalDate getFiledAt() { return filedAt; }
	public String getSummary() { return summary; }
	public String getGuidance() { return guidance; }
	public String getGuidanceDetail() { return guidanceDetail; }
	public String getTone() { return tone; }
	public double getScore() { return score.doubleValue(); }
	public String getPayload() { return payload; }
	public int getVerifiedFacts() { return verifiedFacts; }
	public int getDroppedFacts() { return droppedFacts; }
	public int getSourceChars() { return sourceChars; }
	public String getModel() { return model; }
	public Instant getCreatedAt() { return createdAt; }
}
