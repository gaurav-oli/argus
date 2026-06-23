package com.argus.portfolio;

import com.argus.common.BadRequestException;
import com.argus.common.NotFoundException;
import com.argus.marketdata.FxRateService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual add/edit/remove of positions (Story 3.7, FR-5). A manual position is one lot; edits go
 * through the lot then {@link PositionAcbService#recompute} (lots are the cost source of truth).
 * FX is resolved exactly like the import path. Every change is audited with a timestamp and the
 * live snapshot is re-pushed so the UI updates immediately. Data edits to a multi-lot position
 * (e.g. after a split) are rejected — lot-level editing is a later enhancement.
 */
@Service
public class ManualPositionService {

	private final PositionRepository positions;
	private final PositionLotRepository lots;
	private final PositionAcbService acb;
	private final FxRateService fx;
	private final PositionAuditRepository audit;
	private final LivePortfolioService live;

	public ManualPositionService(PositionRepository positions, PositionLotRepository lots,
			PositionAcbService acb, FxRateService fx, PositionAuditRepository audit, LivePortfolioService live) {
		this.positions = positions;
		this.lots = lots;
		this.acb = acb;
		this.fx = fx;
		this.audit = audit;
		this.live = live;
	}

	@Transactional
	public PositionView add(String ticker, String companyName, BigDecimal shares, BigDecimal costBasis,
			String currency, LocalDate acquisitionDate) {
		String tk = requireTicker(ticker);
		String ccy = requireCurrency(currency);
		requirePositiveShares(shares);
		requireNonNegativeCost(costBasis);

		Position position = positions.save(new Position(tk, blankToNull(companyName), shares, costBasis,
				ccy, acquisitionDate, false, "manual"));
		lots.save(buildLot(position.getId(), shares, costBasis, ccy, acquisitionDate));
		acb.recompute(position);
		record(PositionAudit.CREATED, tk, "Added " + shares + " " + tk + " @ " + costBasis + " " + ccy);
		live.pushCurrent();
		return PositionView.of(position);
	}

	@Transactional
	public PositionView edit(long id, String companyName, String ticker, BigDecimal shares,
			BigDecimal costBasis, String currency, LocalDate acquisitionDate) {
		Position position = positions.findById(id)
				.orElseThrow(() -> new NotFoundException("Position", String.valueOf(id)));

		if (companyName != null) {
			position.setCompanyName(blankToNull(companyName));
		}
		if (ticker != null && !ticker.isBlank()) {
			position.applyTickerChange(ticker.trim().toUpperCase());
		}

		boolean dataEdit = shares != null || costBasis != null || currency != null || acquisitionDate != null;
		if (dataEdit) {
			List<PositionLot> positionLots = lots.findByPositionIdOrderByTradeDateAsc(id);
			if (positionLots.size() != 1) {
				throw new BadRequestException(
						"This position has multiple lots — edit its shares/cost is not supported yet");
			}
			PositionLot lot = positionLots.get(0);
			BigDecimal newShares = shares != null ? shares : lot.getShares();
			BigDecimal newCost = costBasis != null ? costBasis : lot.getTotalCost();
			String newCcy = currency != null ? requireCurrency(currency) : lot.getTradeCurrency();
			LocalDate newDate = acquisitionDate != null ? acquisitionDate : lot.getTradeDate();
			requirePositiveShares(newShares);
			requireNonNegativeCost(newCost);
			Fx resolved = resolveFx(newCcy, newDate);
			lot.edit(newShares, newCost, newCcy, newDate, resolved.rate(), resolved.estimated());
			lots.save(lot);
		}

		acb.recompute(position);
		record(PositionAudit.UPDATED, position.getTicker(), "Edited " + position.getTicker());
		live.pushCurrent();
		return PositionView.of(position);
	}

	@Transactional
	public void remove(long id) {
		Position position = positions.findById(id)
				.orElseThrow(() -> new NotFoundException("Position", String.valueOf(id)));
		String ticker = position.getTicker();
		positions.delete(position); // lots cascade (FK ON DELETE CASCADE)
		record(PositionAudit.REMOVED, ticker, "Removed " + ticker);
		live.pushCurrent();
	}

	@Transactional(readOnly = true)
	public List<AuditEntry> recentAudit() {
		return audit.findTop50ByOrderByCreatedAtDesc().stream()
				.map(a -> new AuditEntry(a.getId(), a.getTicker(), a.getAction(), a.getDetail(), a.getCreatedAt()))
				.toList();
	}

	private PositionLot buildLot(Long positionId, BigDecimal shares, BigDecimal cost, String ccy, LocalDate date) {
		Fx resolved = resolveFx(ccy, date);
		return new PositionLot(positionId, shares, cost, ccy, date, resolved.rate(), resolved.estimated());
	}

	/** Purchase-time FX, identical to the import path: CAD→1, USD+date→BoC lookup, else estimated. */
	private Fx resolveFx(String currency, LocalDate date) {
		if ("CAD".equalsIgnoreCase(currency)) {
			return new Fx(BigDecimal.ONE, false);
		}
		if (date != null) {
			Optional<BigDecimal> rate = fx.usdCadOn(date);
			return new Fx(rate.orElse(null), rate.isEmpty());
		}
		return new Fx(null, true);
	}

	private void record(String action, String ticker, String detail) {
		audit.save(new PositionAudit(ticker, action, detail));
	}

	private static String requireTicker(String ticker) {
		if (ticker == null || ticker.isBlank()) {
			throw new BadRequestException("ticker is required");
		}
		return ticker.trim().toUpperCase();
	}

	private static String requireCurrency(String currency) {
		if (currency == null || currency.isBlank()) {
			throw new BadRequestException("currency is required");
		}
		return currency.trim().toUpperCase();
	}

	private static void requirePositiveShares(BigDecimal shares) {
		if (shares == null || shares.signum() <= 0) {
			throw new BadRequestException("shares must be positive");
		}
	}

	private static void requireNonNegativeCost(BigDecimal cost) {
		if (cost == null || cost.signum() < 0) {
			throw new BadRequestException("cost basis must be zero or positive");
		}
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	private record Fx(BigDecimal rate, boolean estimated) {
	}
}
