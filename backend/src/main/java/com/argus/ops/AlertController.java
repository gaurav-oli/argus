package com.argus.ops;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard Live Alerts feed (Epic 9). Session-gated like all of /api. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

	private final AlertFeedService service;

	public AlertController(AlertFeedService service) {
		this.service = service;
	}

	/** Real, tiered alerts composed from the agents' output (replaces the old mock feed). */
	@GetMapping("/live")
	public List<AlertView> live() {
		return service.live();
	}
}
