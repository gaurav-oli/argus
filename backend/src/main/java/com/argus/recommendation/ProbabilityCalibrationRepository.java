package com.argus.recommendation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the isotonic probability-calibration curve ({@link ProbabilityCalibrationBin}). */
public interface ProbabilityCalibrationRepository extends JpaRepository<ProbabilityCalibrationBin, Short> {
}
