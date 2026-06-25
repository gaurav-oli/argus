package com.argus.portfolio;

import com.argus.common.BadRequestException;
import com.argus.common.ConflictException;
import com.argus.common.NotFoundException;
import com.argus.marketdata.FxRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates PDF statement import (Story 3.1) and per-lot CAD ACB (Story 3.2). Parse → stage a
 * pending batch → (on confirm) write a {@link Position} + one {@link PositionLot} per holding,
 * resolve each lot's purchase-time USD/CAD, and compute the weighted-average ACB. Upload alone
 * never writes positions (confirm-before-overwrite), and a second confirm is rejected via the
 * row-locked status check.
 */
@Service
public class PortfolioImportService {

	private static final TypeReference<List<ParsedHolding>> HOLDINGS_TYPE = new TypeReference<>() {
	};

	private final StatementParser parser;
	private final LlmStatementParser llmParser;
	private final PortfolioImportRepository imports;
	private final PositionRepository positions;
	private final PositionLotRepository lots;
	private final PositionAcbService acbService;
	private final FxRateService fx;
	private final org.springframework.context.ApplicationEventPublisher events;

	// Jackson 3 (tools.jackson) — no injectable ObjectMapper bean in this Boot 4 context; handles
	// LocalDate/BigDecimal natively for the staged-preview JSON round-trip.
	private final ObjectMapper json = JsonMapper.builder().build();

	public PortfolioImportService(StatementParser parser, LlmStatementParser llmParser,
			PortfolioImportRepository imports, PositionRepository positions, PositionLotRepository lots,
			PositionAcbService acbService, FxRateService fx,
			org.springframework.context.ApplicationEventPublisher events) {
		this.parser = parser;
		this.llmParser = llmParser;
		this.imports = imports;
		this.positions = positions;
		this.lots = lots;
		this.acbService = acbService;
		this.fx = fx;
		this.events = events;
	}

	/** Parse the uploaded PDF and persist a pending import batch holding the preview. */
	@Transactional
	public ImportPreview stageImport(String filename, byte[] pdfBytes) {
		return stage(filename, parser.parse(pdfBytes));
	}

	/** Same as {@link #stageImport} but uses the LLM-assisted parser (robust to real bank layouts). */
	@Transactional
	public ImportPreview stageImportLlm(String filename, byte[] pdfBytes) {
		return stage(filename, llmParser.parse(pdfBytes));
	}

	private ImportPreview stage(String filename, StatementParser.ParseResult result) {
		PortfolioImport batch = new PortfolioImport(filename, write(result.holdings()), result.message());
		PortfolioImport saved = imports.save(batch);
		return new ImportPreview(saved.getId(), saved.getFilename(), saved.getStatus(),
				saved.getMessage(), result.holdings());
	}

	/** Commit a pending import: one position + lot per holding, with purchase-time FX resolved. */
	@Transactional
	public List<PositionView> confirmImport(long importId) {
		// Row write-lock so two concurrent confirms can't both read 'pending' and double-commit.
		PortfolioImport batch = imports.findByIdForUpdate(importId)
				.orElseThrow(() -> new NotFoundException("Import", String.valueOf(importId)));
		if (!PortfolioImport.PENDING.equals(batch.getStatus())) {
			throw new ConflictException("Import " + importId + " is already " + batch.getStatus());
		}

		List<Position> created = new ArrayList<>();
		for (ParsedHolding h : read(batch.getRawHoldings())) {
			Position position = positions.save(new Position(h.ticker(), h.companyName(), h.shares(),
					h.costBasis(), h.costBasisCurrency(), h.acquisitionDate(), h.needsReview(), "pdf_import"));
			lots.save(newLot(position.getId(), h));
			acbService.recompute(position);
			created.add(position);
		}

		batch.markConfirmed();
		imports.save(batch);
		// Re-subscribe the live price feed to the new tickers (no restart needed).
		events.publishEvent(new PortfolioChangedEvent());
		return created.stream().map(PositionView::of).toList();
	}

	/**
	 * Confirm/override the purchase FX for a position (Story 3.2 AC #5): supply a {@code rate}
	 * directly, or a {@code date} to look one up. Applies it to the position's estimated lots,
	 * clears the estimate, and recomputes the ACB.
	 */
	@Transactional
	public PositionView confirmFx(long positionId, BigDecimal rate, LocalDate date) {
		Position position = positions.findById(positionId)
				.orElseThrow(() -> new NotFoundException("Position", String.valueOf(positionId)));

		BigDecimal resolved;
		if (rate != null) {
			resolved = rate;
		} else if (date != null) {
			resolved = fx.usdCadOn(date)
					.orElseThrow(() -> new BadRequestException("No USD/CAD rate available for " + date));
		} else {
			throw new BadRequestException("Provide either a rate or a date");
		}

		List<PositionLot> positionLots = lots.findByPositionIdOrderByTradeDateAsc(positionId);
		for (PositionLot lot : positionLots) {
			if (lot.isFxEstimated()) {
				lot.applyFx(resolved, false);
			}
		}
		lots.saveAll(positionLots);
		acbService.recompute(position);
		return PositionView.of(position);
	}

	@Transactional(readOnly = true)
	public List<PositionView> listPositions() {
		return positions.findAllByOrderByTickerAsc().stream().map(PositionView::of).toList();
	}

	/** Build a lot for an imported holding, resolving its purchase-time USD/CAD. */
	private PositionLot newLot(Long positionId, ParsedHolding h) {
		String currency = h.costBasisCurrency();
		BigDecimal fxToCad;
		boolean estimated;
		if ("CAD".equalsIgnoreCase(currency)) {
			fxToCad = BigDecimal.ONE; // CAD cost is already in CAD
			estimated = false;
		} else if (h.acquisitionDate() != null) {
			Optional<BigDecimal> rate = fx.usdCadOn(h.acquisitionDate());
			fxToCad = rate.orElse(null);
			estimated = rate.isEmpty(); // no rate for that date → flag, await confirm
		} else {
			fxToCad = null; // unknown purchase date → can't price the FX
			estimated = true;
		}
		return new PositionLot(positionId, h.shares(), h.costBasis(), currency, h.acquisitionDate(),
				fxToCad, estimated);
	}

	private String write(List<ParsedHolding> holdings) {
		return json.writeValueAsString(holdings);
	}

	private List<ParsedHolding> read(String rawHoldings) {
		return json.readValue(rawHoldings, HOLDINGS_TYPE);
	}
}
