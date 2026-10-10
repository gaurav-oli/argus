package com.argus.learning;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** S-B4 — the pattern library's logged consultations; read-only, session-gated like the rest of /api/learning. */
@RestController
@RequestMapping("/api/learning/patterns")
public class PatternController {

	private final PatternLibraryService patterns;

	public PatternController(PatternLibraryService patterns) {
		this.patterns = patterns;
	}

	/** Most recent pre-entry checks first; {@code ticker} narrows to one name. */
	@GetMapping
	public List<PatternLibraryService.CheckView> recent(@RequestParam(required = false) String ticker,
			@RequestParam(defaultValue = "30") int limit) {
		return patterns.recent(ticker, limit);
	}

	/** How often each advice was given over the last {@code days} (default 30). */
	@GetMapping("/summary")
	public Map<String, Long> summary(@RequestParam(defaultValue = "30") int days) {
		return patterns.actionCounts(Math.max(1, Math.min(days, 365)));
	}
}
