package com.argus.briefing;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Morning Briefing endpoints (Epic 8, FR-16), session-gated under {@code /api/briefing}. The dashboard
 * card reads {@code GET /latest}; {@code POST /generate} forces a fresh generation (manual testing).
 */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

	private final BriefingService service;
	private final BriefingRepository briefings;

	public BriefingController(BriefingService service, BriefingRepository briefings) {
		this.service = service;
		this.briefings = briefings;
	}

	/** The latest briefing, or 204 when none has been generated yet (e.g. before the first 8am run). */
	@GetMapping("/latest")
	public ResponseEntity<BriefingView> latest() {
		return briefings.findFirstByOrderByGeneratedAtDesc()
				.map(b -> ResponseEntity.ok(BriefingView.from(b)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/generate")
	public BriefingView generate() {
		return BriefingView.from(service.generate());
	}

	public record BriefingView(Long id, String headline, String body, Instant generatedAt) {

		static BriefingView from(Briefing b) {
			return new BriefingView(b.getId(), b.getHeadline(), b.getBody(), b.getGeneratedAt());
		}
	}
}
