package com.argus.fundamentals;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** The latest stored {@link Fundamentals} for a ticker (serialized payload plus the fields queried directly). */
@Entity
@Table(name = "fundamentals_snapshot")
public class FundamentalsSnapshot {

	@Id
	private String ticker;

	@Column(nullable = false)
	private String payload;

	@Column(nullable = false)
	private boolean applicable;

	@Column(nullable = false)
	private BigDecimal score;

	@Column(nullable = false)
	private String bias;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	protected FundamentalsSnapshot() {
		// JPA
	}

	public FundamentalsSnapshot(String ticker, String payload, boolean applicable, double score, String bias,
			Instant fetchedAt) {
		this.ticker = ticker;
		this.payload = payload;
		this.applicable = applicable;
		this.score = BigDecimal.valueOf(score).setScale(3, java.math.RoundingMode.HALF_UP);
		this.bias = bias;
		this.fetchedAt = fetchedAt;
	}

	public String getTicker() {
		return ticker;
	}

	public String getPayload() {
		return payload;
	}

	public boolean isApplicable() {
		return applicable;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}
}
