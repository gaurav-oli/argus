package com.argus.portfolio;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Long-term keep/reconsider outlook for each of the signed-in person's own holdings, session-gated
 * under {@code /api/portfolio}. See {@link HoldingOutlookService}. */
@RestController
@RequestMapping("/api/portfolio")
public class HoldingOutlookController {

	private final HoldingOutlookService service;

	public HoldingOutlookController(HoldingOutlookService service) {
		this.service = service;
	}

	@GetMapping("/outlook")
	public List<HoldingOutlookService.HoldingOutlookView> outlook() {
		return service.outlooks();
	}
}
