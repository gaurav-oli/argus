package com.argus.recommendation;

import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only: run Agent 5 over every ticker the paper book holds, right now ({@link OpenTradeReview}). */
@RestController
@RequestMapping("/api/recommendations/open-trades")
public class OpenTradeReviewController {

	private final OpenTradeReview review;
	private final CurrentUserService currentUser;

	public OpenTradeReviewController(OpenTradeReview review, CurrentUserService currentUser) {
		this.review = review;
		this.currentUser = currentUser;
	}

	/** Start a review in the background (or return the one already running); poll {@link #status} for progress. */
	@PostMapping("/review")
	public OpenTradeReview.JobStatus reviewNow(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		return review.start();
	}

	/** The running or most recent review — its pipeline step, progress and results so far. 204 if none has run. */
	@GetMapping("/review")
	public org.springframework.http.ResponseEntity<OpenTradeReview.JobStatus> status(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		OpenTradeReview.JobStatus st = review.status();
		return st == null ? org.springframework.http.ResponseEntity.noContent().build() : org.springframework.http.ResponseEntity.ok(st);
	}
}
