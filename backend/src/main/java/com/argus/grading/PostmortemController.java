package com.argus.grading;

import com.argus.recommendation.ShadowBook;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Post-mortem buckets and the shadow-vs-real comparison for the Agents page (strategy rebuild, Phase 2). */
@RestController
@RequestMapping("/api/postmortems")
public class PostmortemController {

	private final PostmortemService postmortems;
	private final ShadowBook shadowBook;

	public PostmortemController(PostmortemService postmortems, ShadowBook shadowBook) {
		this.postmortems = postmortems;
		this.shadowBook = shadowBook;
	}

	@GetMapping("/summary")
	public Map<String, Object> summary(@RequestParam(defaultValue = "30") int days) {
		int d = Math.max(1, Math.min(365, days));
		return Map.of("days", d, "buckets", postmortems.summary(d), "books", shadowBook.summary(d));
	}

	@GetMapping
	public List<Map<String, Object>> trades(@RequestParam String book, @RequestParam String bucket,
			@RequestParam(defaultValue = "30") int days) {
		PostmortemBuckets.Bucket.valueOf(bucket); // reject unknown buckets
		if (!"REAL".equals(book) && !"SHADOW".equals(book)) {
			throw new IllegalArgumentException("book must be REAL or SHADOW");
		}
		return postmortems.trades(book, bucket, Math.max(1, Math.min(365, days)));
	}
}
