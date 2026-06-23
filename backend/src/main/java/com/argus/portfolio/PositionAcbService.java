package com.argus.portfolio;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes a position's cached weighted-average ACB aggregates from its lots (Stories 3.2/3.3).
 * Single home for the recompute so the import-confirm path and the corporate-action path stay in
 * sync — lots are the source of truth; {@code positions} caches the aggregates.
 */
@Service
public class PositionAcbService {

	private final AcbCalculator acb;
	private final PositionLotRepository lots;
	private final PositionRepository positions;

	public PositionAcbService(AcbCalculator acb, PositionLotRepository lots, PositionRepository positions) {
		this.acb = acb;
		this.lots = lots;
		this.positions = positions;
	}

	@Transactional
	public void recompute(Position position) {
		AcbCalculator.Acb computed = acb.compute(lots.findByPositionIdOrderByTradeDateAsc(position.getId()));
		position.updateAcbCaches(computed.shares(), computed.costBasis(), computed.currency(),
				computed.cadAcb(), computed.fxEstimated());
		positions.save(position);
	}
}
