package com.argus.portfolio;

import com.argus.common.BadRequestException;
import com.argus.common.PayloadTooLargeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

	/**
	 * Controller-level byte ceiling, enforced in addition to the servlet multipart limit so the
	 * cap is exercised by MockMvc (which bypasses the servlet multipart resolver). Mirrors the
	 * {@code spring.servlet.multipart.max-file-size} default (15MB).
	 */
	private final long maxFileBytes;

	public PortfolioImportController(PortfolioImportService service,
			@Value("${argus.portfolio.import.max-file-bytes:15728640}") long maxFileBytes) {
		this.service = service;
		this.maxFileBytes = maxFileBytes;
	}

	@PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public ImportPreview upload(@RequestParam("file") MultipartFile file) {
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
		return service.stageImport(originalName(file), readBytes(file));
	}

	@PostMapping("/imports/{id}/confirm")
	public List<PositionView> confirm(@PathVariable long id) {
		return service.confirmImport(id);
	}

	@GetMapping("/positions")
	public List<PositionView> positions() {
		return service.listPositions();
	}

	private static boolean isPdf(MultipartFile file) {
		String contentType = file.getContentType();
		if (MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(contentType)) {
			return true;
		}
		String name = file.getOriginalFilename();
		return name != null && name.toLowerCase().endsWith(".pdf");
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
