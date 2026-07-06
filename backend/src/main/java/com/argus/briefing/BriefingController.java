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
	private final MarketPulseService marketPulse;

	public BriefingController(BriefingService service, BriefingRepository briefings,
			MarketPulseService marketPulse) {
		this.service = service;
		this.briefings = briefings;
		this.marketPulse = marketPulse;
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

	/** The latest market pulse, or 204 when none has been generated yet. */
	@GetMapping("/market-pulse")
	public ResponseEntity<MarketPulseView> marketPulse() {
		return marketPulse.current()
				.map(p -> ResponseEntity.ok(MarketPulseView.from(p, false)))
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** Re-scan recent market-impacting news and re-summarize; {@code hasUpdates=false} if nothing new. */
	@PostMapping("/market-pulse/refresh")
	public MarketPulseView refreshMarketPulse() {
		MarketPulseService.Result r = marketPulse.refresh();
		return MarketPulseView.from(r.pulse(), r.hasUpdates());
	}

	public record BriefingView(Long id, String headline, String body, Instant generatedAt) {

		static BriefingView from(Briefing b) {
			return new BriefingView(b.getId(), b.getHeadline(), b.getBody(), b.getGeneratedAt());
		}
	}

	/**
	 * The market pulse for the dashboard. {@code hasUpdates} is true only on a refresh that actually
	 * re-summarized (new news arrived); false means "nothing major since we last checked".
	 */
	public record MarketPulseView(String summary, int articleCount, Instant generatedAt, boolean hasUpdates) {

		static MarketPulseView from(MarketPulse p, boolean hasUpdates) {
			return new MarketPulseView(p.getSummary(), p.getArticleCount(), p.getGeneratedAt(), hasUpdates);
		}
	}
}
