package com.argus.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One entry in the DB-backed macro/political keyword list ({@code macro_keyword}, V50) — either
 * hand-curated at seed time ({@code source = "seed"}, {@link MacroRelevanceTagger#DEFAULT_KEYWORDS})
 * or discovered by {@link MacroKeywordLearningService} from real misses ({@code source = "learned"}).
 */
@Entity
@Table(name = "macro_keyword")
public class MacroKeyword {

	@Id
	private String keyword;

	@Column(name = "added_at", nullable = false)
	private Instant addedAt = Instant.now();

	@Column(nullable = false)
	private String source;

	private String why;

	@Column(name = "corroborating_misses")
	private Integer corroboratingMisses;

	protected MacroKeyword() {
		// JPA
	}

	public MacroKeyword(String keyword, String source, String why, Integer corroboratingMisses) {
		this.keyword = keyword;
		this.source = source;
		this.why = why;
		this.corroboratingMisses = corroboratingMisses;
	}

	public String getKeyword() {
		return keyword;
	}

	public Instant getAddedAt() {
		return addedAt;
	}

	public String getSource() {
		return source;
	}

	public String getWhy() {
		return why;
	}

	public Integer getCorroboratingMisses() {
		return corroboratingMisses;
	}
}
