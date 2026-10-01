package com.argus.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One published cross-sectional return predictor — a trading strategy from the academic literature, with the
 * paper that proposed it, the exact signal definition, the effect size the authors reported, and
 * {@link #replicationGrade} (Chen &amp; Zimmermann's independent verdict on whether it replicates at all).
 *
 * <p>Two fields matter more than the published numbers: {@link #kind} is PLACEBO for the ~114 signals that were
 * published but shown <em>not</em> to predict returns, and {@link #status} only reaches ACTIVE once the strategy
 * has been computed on Argus's own data and survived a chronological hold-out backtest. Publication alone buys a
 * strategy nothing here — the literature's own replication rate is roughly a coin flip.
 */
@Entity
@Table(name = "academic_strategy")
public class AcademicStrategy {

	public enum Kind { PREDICTOR, PLACEBO, DROP }

	/** UNIMPLEMENTED → CANDIDATE (we can compute it) → ACTIVE (it survived the hold-out) or REJECTED. */
	public enum Status { UNIMPLEMENTED, CANDIDATE, ACTIVE, REJECTED }

	@Id
	private String acronym;

	@Column(nullable = false)
	private String name;

	private String authors;
	private Integer year;
	private String journal;

	@Column(nullable = false)
	private String kind;

	@Column(name = "data_category")
	private String dataCategory;

	@Column(name = "economic_category")
	private String economicCategory;

	@Column(columnDefinition = "text")
	private String definition;

	@Column(name = "published_t_stat")
	private BigDecimal publishedTStat;

	@Column(name = "published_return")
	private BigDecimal publishedReturn;

	@Column(name = "sign")
	private BigDecimal sign;

	@Column(name = "ls_quantile")
	private BigDecimal lsQuantile;

	@Column(name = "rebalance_months")
	private BigDecimal rebalanceMonths;

	@Column(name = "stock_weight")
	private String stockWeight;

	@Column(name = "sample_start_year")
	private Integer sampleStartYear;

	@Column(name = "sample_end_year")
	private Integer sampleEndYear;

	@Column(name = "replication_grade")
	private String replicationGrade;

	@Column(name = "gscholar_cites")
	private Integer gscholarCites;

	@Column(nullable = false)
	private boolean computable;

	private String implementation;

	@Column(nullable = false)
	private String status = Status.UNIMPLEMENTED.name();

	@Column(name = "imported_at", nullable = false)
	private Instant importedAt = Instant.now();

	protected AcademicStrategy() {
	}

	public AcademicStrategy(String acronym, String name) {
		this.acronym = acronym;
		this.name = name;
		this.kind = Kind.PREDICTOR.name();
	}

	/** Overwrite the imported fields (the corpus is the source of truth for everything except our own verdicts). */
	public void describe(String name, String authors, Integer year, String journal, Kind kind, String dataCategory,
			String economicCategory, String definition, BigDecimal publishedTStat, BigDecimal publishedReturn, BigDecimal sign,
			BigDecimal lsQuantile, BigDecimal rebalanceMonths, String stockWeight, Integer sampleStartYear,
			Integer sampleEndYear, String replicationGrade, Integer gscholarCites) {
		this.name = name;
		this.authors = authors;
		this.year = year;
		this.journal = journal;
		this.kind = kind.name();
		this.dataCategory = dataCategory;
		this.economicCategory = economicCategory;
		this.definition = definition;
		this.publishedTStat = publishedTStat;
		this.publishedReturn = publishedReturn;
		this.sign = sign;
		this.lsQuantile = lsQuantile;
		this.rebalanceMonths = rebalanceMonths;
		this.stockWeight = stockWeight;
		this.sampleStartYear = sampleStartYear;
		this.sampleEndYear = sampleEndYear;
		this.replicationGrade = replicationGrade;
		this.gscholarCites = gscholarCites;
		this.importedAt = Instant.now();
	}

	/** We have an implementation: the strategy becomes a candidate for validation. */
	public void implementedAs(String signalId) {
		this.implementation = signalId;
		this.computable = true;
		if (status.equals(Status.UNIMPLEMENTED.name())) {
			this.status = Status.CANDIDATE.name();
		}
	}

	public void activate() {
		this.status = Status.ACTIVE.name();
	}

	public void reject() {
		this.status = Status.REJECTED.name();
	}

	public String getAcronym() { return acronym; }
	public String getName() { return name; }
	public String getAuthors() { return authors; }
	public Integer getYear() { return year; }
	public String getJournal() { return journal; }
	public Kind getKind() { return Kind.valueOf(kind); }
	public String getDataCategory() { return dataCategory; }
	public String getEconomicCategory() { return economicCategory; }
	public String getDefinition() { return definition; }
	public BigDecimal getPublishedTStat() { return publishedTStat; }
	public BigDecimal getPublishedReturn() { return publishedReturn; }
	public BigDecimal getSign() { return sign; }
	public BigDecimal getLsQuantile() { return lsQuantile; }
	public BigDecimal getRebalanceMonths() { return rebalanceMonths; }
	public String getStockWeight() { return stockWeight; }
	public Integer getSampleStartYear() { return sampleStartYear; }
	public Integer getSampleEndYear() { return sampleEndYear; }
	public String getReplicationGrade() { return replicationGrade; }
	public Integer getGscholarCites() { return gscholarCites; }
	public boolean isComputable() { return computable; }
	public String getImplementation() { return implementation; }
	public Status getStatus() { return Status.valueOf(status); }
	public Instant getImportedAt() { return importedAt; }

	/** The direction a high reading implies: +1 buy the high end, -1 buy the low end. Defaults to +1. */
	public double signOrDefault() {
		return sign == null ? 1.0 : sign.doubleValue();
	}

	/** A citation line for prompts and the UI. */
	public String citation() {
		return (authors == null ? "?" : authors) + (year == null ? "" : " (" + year + ")")
				+ (journal == null || journal.isBlank() ? "" : ", " + journal);
	}
}
