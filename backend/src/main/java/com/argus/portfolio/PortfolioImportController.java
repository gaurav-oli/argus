package com.argus.portfolio;

import com.argus.common.BadRequestException;
import com.argus.common.PayloadTooLargeException;
import com.argus.security.CurrentUserContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Portfolio statement import + positions (Story 3.1, FR-1). Session-gated under {@code /api/**} by
 * {@link com.argus.security.SessionAuthFilter} (not allowlisted). Errors are RFC 9457 problem+json
 * via the global {@code @RestControllerAdvice}; success returns the resource directly.
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioImportController {

	private final PortfolioImportService service;
	private final StatementImportRunner runner;

	/**
	 * Controller-level byte ceiling, enforced in addition to the servlet multipart limit so the
	 * cap is exercised by MockMvc (which bypasses the servlet multipart resolver). Mirrors the
	 * {@code spring.servlet.multipart.max-file-size} default (15MB).
	 */
	private final long maxFileBytes;

	public PortfolioImportController(PortfolioImportService service, StatementImportRunner runner,
			@Value("${argus.portfolio.import.max-file-bytes:15728640}") long maxFileBytes) {
		this.service = service;
		this.runner = runner;
		this.maxFileBytes = maxFileBytes;
	}

	/**
	 * Upload a statement PDF. {@code mode=auto} (what the Import Statement screen now sends) queues it
	 * on {@link StatementImportRunner}: tries local Gemma with a self-verification loop (falling back
	 * to Haiku only as a last resort), and either auto-applies it to the portfolio or leaves it staged
	 * for review — either way the uploader is told what happened by push notification and email, since
	 * the parse can take a while. The default stays {@code heuristic} (fast, synchronous, immediate
	 * preview) for backward compatibility; {@code mode=llm} is the same shape but AI-assisted.
	 */
	@PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
			@RequestParam(name = "mode", defaultValue = "heuristic") String mode,
			@RequestParam(name = "institution", required = false) String institution) {
		if (file == null || file.isEmpty()) {
			throw new BadRequestException("Missing file");
		}
		if (!isPdf(file)) {
			throw new BadRequestException("File must be a PDF");
		}
		if (file.getSize() > maxFileBytes) {
			throw new PayloadTooLargeException(
					"File exceeds the maximum size of " + (maxFileBytes / (1024 * 1024)) + " MB");
		}
		String name = originalName(file);
		byte[] bytes = readBytes(file);
		if ("auto".equalsIgnoreCase(mode)) {
			runner.submit(CurrentUserContext.get(), name, bytes, institution);
			return ResponseEntity.accepted().body(new ImportAccepted("processing",
					"We're reading \"" + name + "\" now — you'll get a notification (and an email) once it's done."));
		}
		// "llm" routes to the AI-assisted parser (robust to real multi-account bank statements); kept as
		// an explicit, synchronous, manual-preview option alongside the default automatic path above.
		ImportPreview preview = "llm".equalsIgnoreCase(mode) ? service.stageImportLlm(name, bytes, institution)
				: service.stageImport(name, bytes);
		return ResponseEntity.status(HttpStatus.CREATED).body(preview);
	}

	@PostMapping("/imports/{id}/confirm")
	public List<PositionView> confirm(@PathVariable long id) {
		return service.confirmImport(id);
	}

	/** Imports the automatic ({@code mode=auto}) path staged but wasn't confident enough to auto-apply
	 * — still awaiting a manual look, so one never silently disappears after its notification email. */
	@GetMapping("/imports/pending")
	public List<ImportPreview> pendingImports() {
		return service.listPending();
	}

	@GetMapping("/positions")
	public List<PositionView> positions() {
		return service.listPositions();
	}

	/**
	 * Confirm/override a position's purchase-time USD/CAD (Story 3.2, FR-1b). Body carries exactly
	 * one of {@code rate} (used directly) or {@code date} (looked up); clears the FX-estimated flag
	 * and recomputes the CAD ACB.
	 */
	/** Sane USD/CAD band — well above any real rate; guards the {@code numeric(18,8)} columns. */
	private static final java.math.BigDecimal MAX_FX_RATE = new java.math.BigDecimal("1000");

	@PutMapping("/positions/{id}/fx")
	public PositionView confirmFx(@PathVariable long id, @RequestBody(required = false) FxConfirmation body) {
		boolean hasRate = body != null && body.rate() != null;
		boolean hasDate = body != null && body.date() != null;
		if (!hasRate && !hasDate) {
			throw new BadRequestException("Provide either a rate or a date");
		}
		if (hasRate && hasDate) {
			throw new BadRequestException("Provide either a rate or a date, not both");
		}
		if (hasRate) {
			BigDecimal rate = body.rate();
			// Bound scale + magnitude so a stray value can't overflow/silently round numeric(18,8).
			if (rate.signum() <= 0 || rate.compareTo(MAX_FX_RATE) > 0) {
				throw new BadRequestException("Rate must be positive and within a realistic range");
			}
			if (rate.scale() > 8) {
				throw new BadRequestException("Rate supports at most 8 decimal places");
			}
		}
		return service.confirmFx(id, body.rate(), body.date());
	}

	/** Purchase-FX confirmation: an explicit {@code rate}, or a {@code date} to look one up. */
	public record FxConfirmation(BigDecimal rate, LocalDate date) {
	}

	/**
	 * S-A6 / M4: require {@code %PDF} magic bytes — content-type or a {@code .pdf} filename alone
	 * is not enough (spoofable).
	 */
	private static boolean isPdf(MultipartFile file) {
		try {
			byte[] bytes = file.getBytes();
			return looksLikePdf(bytes);
		} catch (IOException ex) {
			return false;
		}
	}

	/** True when the payload starts with the PDF magic header. */
	static boolean looksLikePdf(byte[] bytes) {
		return bytes != null && bytes.length >= 4
				&& bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
	}

	private static String originalName(MultipartFile file) {
		String name = file.getOriginalFilename();
		return (name == null || name.isBlank()) ? "statement.pdf" : name;
	}

	private static byte[] readBytes(MultipartFile file) {
		try {
			return file.getBytes();
		} catch (IOException ex) {
			throw new UncheckedIOException("Could not read uploaded file", ex);
		}
	}
}
