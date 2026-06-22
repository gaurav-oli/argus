package com.argus.marketdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Cached historical FX rate (Story 3.2). Keyed by {@code (pair, rateDate)} where {@code rateDate}
 * is the date that was requested — so a repeat lookup of the same date is served from cache and
 * never re-fetches the upstream source. The stored {@code rate} is the resolved value (nearest
 * prior business day).
 */
@Entity
@Table(name = "fx_rates")
@IdClass(FxRate.Key.class)
public class FxRate {

	@Id
	private String pair;

	@Id
	@Column(name = "rate_date")
	private LocalDate rateDate;

	@Column(nullable = false)
	private BigDecimal rate;

	@Column(nullable = false)
	private String source;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt = Instant.now();

	protected FxRate() {
		// JPA
	}

	public FxRate(String pair, LocalDate rateDate, BigDecimal rate, String source) {
		this.pair = pair;
		this.rateDate = rateDate;
		this.rate = rate;
		this.source = source;
	}

	public String getPair() {
		return pair;
	}

	public LocalDate getRateDate() {
		return rateDate;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public String getSource() {
		return source;
	}

	/** Composite key for {@link FxRate}. */
	public static class Key implements Serializable {

		private String pair;
		private LocalDate rateDate;

		public Key() {
		}

		public Key(String pair, LocalDate rateDate) {
			this.pair = pair;
			this.rateDate = rateDate;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Key key)) {
				return false;
			}
			return Objects.equals(pair, key.pair) && Objects.equals(rateDate, key.rateDate);
		}

		@Override
		public int hashCode() {
			return Objects.hash(pair, rateDate);
		}
	}
}
