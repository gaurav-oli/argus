package com.argus.recommendation;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trade Journal read endpoints (Story 11.1, F22), session-gated under {@code /api/journal} like all
 * {@code /api/**} paths. A distinct resource from {@code /api/recommendations} — a journal entry is a
 * decided {@link TradeDecision}, not a recommendation sub-resource.
 */
@RestController
@RequestMapping("/api/journal")
public class JournalController {

	private final JournalService journal;

	public JournalController(JournalService journal) {
		this.journal = journal;
	}

	@GetMapping
	public List<JournalService.JournalEntryView> list() {
		return journal.list();
	}

	@GetMapping("/{decisionId}")
	public JournalService.JournalDetailView detail(@PathVariable Long decisionId) {
		return journal.detail(decisionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}
}
