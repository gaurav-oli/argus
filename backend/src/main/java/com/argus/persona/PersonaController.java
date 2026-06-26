package com.argus.persona;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F11 — the four personas' verdicts on a recommendation. Session-gated like all /api. */
@RestController
@RequestMapping("/api/recommendations")
public class PersonaController {

	/** One persona's take for the UI. */
	public record PersonaView(String persona, String key, String lens, String stance, String rationale) {
	}

	private final PersonaService personas;

	public PersonaController(PersonaService personas) {
		this.personas = personas;
	}

	@GetMapping("/{id}/personas")
	public List<PersonaView> personas(@PathVariable Long id) {
		return personas.verdictsFor(id).stream()
				.map(v -> new PersonaView(v.getPersona().displayName(), v.getPersona().name(),
						v.getPersona().lens(), v.getStance().name(), v.getRationale()))
				.toList();
	}
}
