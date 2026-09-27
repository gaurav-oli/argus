package com.argus.filings;

import com.argus.common.NotFoundException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 14's filings digests for the Intelligence page, session-gated under {@code /api/filings}. */
@RestController
@RequestMapping("/api/filings")
public class FilingsController {

	private final FilingDigestService service;

	public FilingsController(FilingDigestService service) {
		this.service = service;
	}

	/** Each ticker's newest filing read, the most decisive (either direction) first. */
	@GetMapping
	public List<Row> list() {
		return service.latestPerTicker().stream()
				.map(d -> new Row(d.getTicker(), d.getForm(), d.getKind().name(), d.getFiledAt(), d.getSummary(), d.getGuidance(), d.getTone(), d.getScore()))
				.sorted(Comparator.comparingDouble((Row r) -> Math.abs(r.score())).reversed()).toList();
	}

	@GetMapping("/{ticker}")
	public FilingView detail(@PathVariable String ticker) {
		return service.view(ticker.trim().toUpperCase(Locale.ROOT)).orElseThrow(() -> new NotFoundException("Filings", ticker));
	}

	/** "Read now" — fetch and digest anything new for this ticker (idempotent). */
	@PostMapping("/{ticker}/refresh")
	public FilingView refresh(@PathVariable String ticker) {
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		service.refresh(t);
		return service.view(t).orElseThrow(() -> new NotFoundException("Filings", t));
	}

	public record Row(String ticker, String form, String kind, LocalDate filedAt, String summary, String guidance, String tone, double score) {
	}
}
