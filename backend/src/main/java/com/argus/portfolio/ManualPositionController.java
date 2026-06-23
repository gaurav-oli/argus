package com.argus.portfolio;

import com.argus.common.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual position CRUD + audit (Story 3.7, FR-5), session-gated under {@code /api/portfolio}.
 * Add/edit/remove go through {@link ManualPositionService}; each change is audited and re-broadcasts
 * the live snapshot. Errors are RFC 9457; success returns the resource directly (DELETE → 204).
 */
@RestController
@RequestMapping("/api/portfolio")
public class ManualPositionController {

	private final ManualPositionService service;

	public ManualPositionController(ManualPositionService service) {
		this.service = service;
	}

	@PostMapping("/positions")
	@ResponseStatus(HttpStatus.CREATED)
	public PositionView add(@RequestBody(required = false) AddRequest body) {
		if (body == null) {
			throw new BadRequestException("Missing request body");
		}
		return service.add(body.ticker(), body.companyName(), body.shares(), body.costBasis(),
				body.currency(), body.acquisitionDate());
	}

	@PutMapping("/positions/{id}")
	public PositionView edit(@PathVariable long id, @RequestBody(required = false) EditRequest body) {
		if (body == null) {
			throw new BadRequestException("Missing request body");
		}
		return service.edit(id, body.companyName(), body.ticker(), body.shares(), body.costBasis(),
				body.currency(), body.acquisitionDate());
	}

	@DeleteMapping("/positions/{id}")
	public ResponseEntity<Void> remove(@PathVariable long id) {
		service.remove(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/audit")
	public List<AuditEntry> audit() {
		return service.recentAudit();
	}

	/** Add a position: a ticker + holding details. */
	public record AddRequest(String ticker, String companyName, BigDecimal shares, BigDecimal costBasis,
			String currency, LocalDate acquisitionDate) {
	}

	/** Edit a position: any subset of fields (null = unchanged). */
	public record EditRequest(String companyName, String ticker, BigDecimal shares, BigDecimal costBasis,
			String currency, LocalDate acquisitionDate) {
	}
}
