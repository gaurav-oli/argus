package com.argus.intelligence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for logged keyword-list misses ({@link MacroKeywordMiss}). */
public interface MacroKeywordMissRepository extends JpaRepository<MacroKeywordMiss, Long> {

	List<MacroKeywordMiss> findByReviewedFalseOrderByDetectedAtAsc();

	long countByReviewedFalse();
}
