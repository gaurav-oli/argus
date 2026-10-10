package com.argus.learning;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-B3 — per-trade lessons, read-only and session-gated like the rest of {@code /api/learning}. The paper
 * book is shared across users, so lessons are too; they carry no portfolio data.
 */
@RestController
@RequestMapping("/api/learning/lessons")
public class TradeLessonController {

	private final TradeLessonService lessons;

	public TradeLessonController(TradeLessonService lessons) {
		this.lessons = lessons;
	}

	/** Most recent lessons first; {@code ticker} narrows to one name (for the Intelligence ticker view). */
	@GetMapping
	public List<TradeLessonService.LessonView> recent(@RequestParam(required = false) String ticker,
			@RequestParam(defaultValue = "30") int limit) {
		return lessons.recent(ticker, limit);
	}

	/** The lesson for one closed paper trade (by {@code simulated_trades.id}), 404 until it's written. */
	@GetMapping("/trade/{tradeId}")
	public ResponseEntity<TradeLessonService.LessonView> forTrade(@PathVariable long tradeId) {
		return lessons.forTrade(tradeId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
	}
}
