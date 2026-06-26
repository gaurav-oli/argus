package com.argus.persona;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One persona's cached verdict on a recommendation (F11). */
@Entity
@Table(name = "persona_verdicts")
public class PersonaVerdict {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recommendation_id", nullable = false)
	private Long recommendationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Persona persona;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PersonaStance stance;

	@Column(columnDefinition = "text")
	private String rationale;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected PersonaVerdict() {
		// JPA
	}

	public PersonaVerdict(Long recommendationId, Persona persona, PersonaStance stance, String rationale) {
		this.recommendationId = recommendationId;
		this.persona = persona;
		this.stance = stance;
		this.rationale = rationale;
	}

	public Persona getPersona() {
		return persona;
	}

	public PersonaStance getStance() {
		return stance;
	}

	public String getRationale() {
		return rationale;
	}
}
