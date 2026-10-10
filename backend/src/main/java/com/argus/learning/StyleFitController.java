package com.argus.learning;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-B6 — which playbooks win on which kinds of names; read-only, session-gated like the rest of /api/learning. */
@RestController
@RequestMapping("/api/learning/style-fit")
public class StyleFitController {

	private final StyleFitService fit;

	public StyleFitController(StyleFitService fit) {
		this.fit = fit;
	}

	@GetMapping
	public StyleFitService.Report report() {
		return fit.report();
	}
}
