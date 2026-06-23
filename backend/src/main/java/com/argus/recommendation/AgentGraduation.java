package com.argus.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Single-row holder of Agent 5's current {@link GraduationState} (Story 6.6); always id = 1. */
@Entity
@Table(name = "agent_graduation")
public class AgentGraduation {

	static final int SINGLETON_ID = 1;

	@Id
	private Integer id = SINGLETON_ID;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GraduationState state = GraduationState.SHADOW;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected AgentGraduation() {
		// JPA
	}

	public GraduationState getState() {
		return state;
	}

	public void setState(GraduationState state) {
		this.state = state;
		this.updatedAt = Instant.now();
	}
}
