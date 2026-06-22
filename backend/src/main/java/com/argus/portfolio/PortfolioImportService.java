package com.argus.portfolio;

import com.argus.common.ConflictException;
import com.argus.common.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates PDF statement import (Story 3.1, FR-1): parse → stage a pending batch → (on
 * confirm) commit holdings to {@code positions}. Upload alone never writes positions, so confirmed
 * data is never overwritten without explicit confirmation (FR-1 consequence #3).
 */
@Service
public class PortfolioImportService {

	private static final TypeReference<List<ParsedHolding>> HOLDINGS_TYPE = new TypeReference<>() {
	};

	private final StatementParser parser;
	private final PortfolioImportRepository imports;
	private final PositionRepository positions;

	// Jackson 3 (tools.jackson) — the Boot 4 MVC default. Handles the holdings' LocalDate +
	// BigDecimal fields natively (no module), so the service owns a plain mapper for the
	// staged-preview JSON round-trip (no injectable ObjectMapper bean exists in this context).
	private final ObjectMapper json = JsonMapper.builder().build();

	public PortfolioImportService(StatementParser parser, PortfolioImportRepository imports,
			PositionRepository positions) {
		this.parser = parser;
		this.imports = imports;
		this.positions = positions;
	}

	/** Parse the uploaded PDF and persist a pending import batch holding the preview. */
	@Transactional
	public ImportPreview stageImport(String filename, byte[] pdfBytes) {
		StatementParser.ParseResult result = parser.parse(pdfBytes);
		PortfolioImport batch = new PortfolioImport(filename, write(result.holdings()), result.message());
		PortfolioImport saved = imports.save(batch);
		return new ImportPreview(saved.getId(), saved.getFilename(), saved.getStatus(),
				saved.getMessage(), result.holdings());
	}

	/** Commit a pending import's holdings into {@code positions}. Idempotency is enforced by status. */
	@Transactional
	public List<PositionView> confirmImport(long importId) {
		// Row write-lock so two concurrent confirms can't both read 'pending' and double-commit.
		PortfolioImport batch = imports.findByIdForUpdate(importId)
				.orElseThrow(() -> new NotFoundException("Import", String.valueOf(importId)));
		if (!PortfolioImport.PENDING.equals(batch.getStatus())) {
			throw new ConflictException("Import " + importId + " is already " + batch.getStatus());
		}

		List<ParsedHolding> holdings = read(batch.getRawHoldings());
		List<Position> created = holdings.stream()
				.map(h -> new Position(h.ticker(), h.companyName(), h.shares(), h.costBasis(),
						h.costBasisCurrency(), h.acquisitionDate(), h.needsReview(), "pdf_import"))
				.toList();
		positions.saveAll(created);

		batch.markConfirmed();
		imports.save(batch);
		return created.stream().map(PositionView::of).toList();
	}

	@Transactional(readOnly = true)
	public List<PositionView> listPositions() {
		return positions.findAllByOrderByTickerAsc().stream().map(PositionView::of).toList();
	}

	private String write(List<ParsedHolding> holdings) {
		// Jackson 3 throws unchecked JacksonException; these payloads are our own records and
		// won't realistically fail to (de)serialize, so we let it propagate.
		return json.writeValueAsString(holdings);
	}

	private List<ParsedHolding> read(String rawHoldings) {
		return json.readValue(rawHoldings, HOLDINGS_TYPE);
	}
}
