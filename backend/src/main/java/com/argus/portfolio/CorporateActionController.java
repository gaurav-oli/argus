package com.argus.portfolio;

import com.argus.common.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Corporate-actions endpoints (Story 3.3, FR-1c), session-gated under {@code /api/portfolio} by
 * {@link com.argus.security.SessionAuthFilter}. Manual recording is the entry point (a future
 * detector feeds the same service); ambiguous actions land as {@code pending} for confirm/dismiss.
 * Errors are RFC 9457; success returns the resource directly.
 */
@RestController
@RequestMapping("/api/portfolio/corporate-actions")
public class CorporateActionController {

	private final CorporateActionService service;

	public CorporateActionController(CorporateActionService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CorporateActionView record(@RequestBody(required = false) RecordRequest body) {
		if (body == null || body.ticker() == null || body.ticker().isBlank()) {
			throw new BadRequestException("ticker is required");
		}
		if (body.type() == null || body.type().isBlank()) {
			throw new BadRequestException("type is required");
		}
		CorporateActionType type = CorporateActionType.fromCode(body.type());
		return service.record(body.ticker().trim().toUpperCase(), type, body.ratio(), body.newTicker(),
				body.exDate());
	}

	@GetMapping
	public List<CorporateActionView> list() {
		return service.list();
	}

	@PostMapping("/{id}/confirm")
	public CorporateActionView confirm(@PathVariable long id) {
		return service.confirm(id);
	}

	@PostMapping("/{id}/dismiss")
	public CorporateActionView dismiss(@PathVariable long id) {
		return service.dismiss(id);
	}

	/** Manual corporate-action entry (also the shape a future detector would feed). */
	public record RecordRequest(String ticker, String type, BigDecimal ratio, String newTicker,
			LocalDate exDate) {
	}
}
