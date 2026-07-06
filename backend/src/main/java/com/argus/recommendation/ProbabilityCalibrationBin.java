package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One 10-point bin of the isotonic probability-calibration curve (Phase B). {@code calibrated} is the
 * monotone, sample-weighted realized hit rate for stated directional probabilities in
 * {@code [binLow, binHigh)}; null when the bin has too few samples (→ identity, use the stated value).
 */
@Entity
@Table(name = "probability_calibration")
public class ProbabilityCalibrationBin {

	@Id
	@Column(name = "bin_low")
	private Short binLow;

	@Column(name = "bin_high", nullable = false)
	private short binHigh;

	@Column(name = "sample_size", nullable = false)
	private int sampleSize;

	@Column(name = "raw_hit_rate")
	private BigDecimal rawHitRate;

	@Column(name = "calibrated")
	private BigDecimal calibrated;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected ProbabilityCalibrationBin() {
		// JPA
	}

	public ProbabilityCalibrationBin(int binLow) {
		this.binLow = (short) binLow;
		this.binHigh = (short) (binLow + 10);
	}

	void update(int sampleSize, Double rawHitRate, Double calibrated) {
		this.sampleSize = sampleSize;
		this.rawHitRate = rawHitRate == null ? null : BigDecimal.valueOf(rawHitRate);
		this.calibrated = calibrated == null ? null : BigDecimal.valueOf(calibrated);
		this.updatedAt = Instant.now();
	}

	public short getBinLow() {
		return binLow;
	}

	public short getBinHigh() {
		return binHigh;
	}

	public int getSampleSize() {
		return sampleSize;
	}

	public BigDecimal getRawHitRate() {
		return rawHitRate;
	}

	public BigDecimal getCalibrated() {
		return calibrated;
	}
}
