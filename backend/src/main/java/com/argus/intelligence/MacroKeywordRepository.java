package com.argus.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the DB-backed macro keyword list ({@link MacroKeyword}). */
public interface MacroKeywordRepository extends JpaRepository<MacroKeyword, String> {

	/** Learned-keyword count for Agent 8's status note. */
	long countBySource(String source);
}
