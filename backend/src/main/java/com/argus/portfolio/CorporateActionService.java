package com.argus.portfolio;

import com.argus.common.BadRequestException;
import com.argus.common.ConflictException;
import com.argus.common.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies corporate actions to holdings (Story 3.3, FR-1c). Unambiguous splits/reverse-splits/
 * ticker-changes on a single matched position auto-apply; everything ambiguous (no/multiple match,
 * merger, stock dividend, missing ratio, or a rename whose target is already held) is stored
 * {@code pending} for manual confirmation and the position is left untouched. The split math
 * preserves total cost basis (lot shares scale, lot total cost is unchanged) and CAD ACB recomputes
 * via {@link PositionAcbService}.
 */
@Service
public class CorporateActionService {

	private final CorporateActionRepository actions;
	private final PositionRepository positions;
	private final PositionLotRepository lots;
	private final PositionAcbService acbService;

	public CorporateActionService(CorporateActionRepository actions, PositionRepository positions,
			PositionLotRepository lots, PositionAcbService acbService) {
		this.actions = actions;
		this.positions = positions;
		this.lots = lots;
		this.acbService = acbService;
	}

	/** Record an action; auto-apply when unambiguous, else persist as pending (🟡). */
	@Transactional
	public CorporateActionView record(String ticker, CorporateActionType type, BigDecimal ratio,
			String newTicker, LocalDate exDate) {
		List<Position> matches = positions.findByTicker(ticker);
		boolean singleMatch = matches.size() == 1;
		Long positionId = singleMatch ? matches.get(0).getId() : null;
		boolean applicable = isApplicable(type, ratio, newTicker);
		// A rename onto a symbol already held would create a duplicate-ticker collision — never
		// auto-apply that; flag it for manual handling (position merge is a later story).
		boolean targetConflict = involvesRename(type) && newTicker != null && !newTicker.isBlank()
				&& targetHeldElsewhere(newTicker, positionId);
		boolean unambiguous = singleMatch && applicable && !targetConflict;

		CorporateAction action = new CorporateAction(ticker, positionId, type, ratio, newTicker, exDate,
				unambiguous ? null : pendingReason(type, matches.size(), applicable, targetConflict, newTicker),
				"manual");
		actions.save(action);

		if (unambiguous) {
			apply(action, matches.get(0));
			action.markApplied(matches.get(0).getId());
			actions.save(action);
		}
		return CorporateActionView.of(action);
	}

	@Transactional(readOnly = true)
	public List<CorporateActionView> list() {
		return actions.findAllByOrderByCreatedAtDesc().stream().map(CorporateActionView::of).toList();
	}

	/** Apply a pending action after manual confirmation. */
	@Transactional
	public CorporateActionView confirm(long id) {
		// Row write-lock so two concurrent confirms can't both read 'pending' and double-apply.
		CorporateAction action = actions.findByIdForUpdate(id)
				.orElseThrow(() -> new NotFoundException("CorporateAction", String.valueOf(id)));
		if (!CorporateAction.PENDING.equals(action.getStatus())) {
			throw new ConflictException("Corporate action " + id + " is already " + action.getStatus());
		}
		Position position = resolvePosition(action);
		apply(action, position);
		action.markApplied(position.getId());
		actions.save(action);
		return CorporateActionView.of(action);
	}

	@Transactional
	public CorporateActionView dismiss(long id) {
		CorporateAction action = actions.findByIdForUpdate(id)
				.orElseThrow(() -> new NotFoundException("CorporateAction", String.valueOf(id)));
		if (!CorporateAction.PENDING.equals(action.getStatus())) {
			throw new ConflictException("Corporate action " + id + " is already " + action.getStatus());
		}
		action.markDismissed();
		actions.save(action);
		return CorporateActionView.of(action);
	}

	/** Mutate the position/lots per the action type. Never called on the pending (ambiguous) path. */
	private void apply(CorporateAction action, Position position) {
		CorporateActionType type = action.typeEnum();
		switch (type) {
			case SPLIT, REVERSE_SPLIT -> {
				scaleLots(position, requirePositiveRatio(action.getRatio()));
				acbService.recompute(position);
			}
			case TICKER_CHANGE -> {
				String newTicker = requireNewTicker(action.getNewTicker());
				requireTargetFree(newTicker, position.getId());
				position.applyTickerChange(newTicker);
				positions.save(position);
			}
			case MERGER -> {
				boolean hasRatio = action.getRatio() != null && action.getRatio().signum() > 0;
				boolean hasNewTicker = action.getNewTicker() != null && !action.getNewTicker().isBlank();
				if (!hasRatio && !hasNewTicker) {
					throw new BadRequestException("A merger needs a share-exchange ratio and/or a new ticker");
				}
				if (hasRatio) {
					scaleLots(position, action.getRatio());
				}
				if (hasNewTicker) {
					requireTargetFree(action.getNewTicker(), position.getId());
					position.applyTickerChange(action.getNewTicker());
				}
				positions.save(position);
				acbService.recompute(position);
			}
			case STOCK_DIVIDEND -> throw new BadRequestException(
					"Stock dividends must be applied as an explicit share/cost adjustment (manual)");
		}
	}

	private void scaleLots(Position position, BigDecimal ratio) {
		List<PositionLot> positionLots = lots.findByPositionIdOrderByTradeDateAsc(position.getId());
		for (PositionLot lot : positionLots) {
			lot.applySplit(ratio);
		}
		lots.saveAll(positionLots);
	}

	private Position resolvePosition(CorporateAction action) {
		if (action.getPositionId() != null) {
			return positions.findById(action.getPositionId())
					.orElseThrow(() -> new NotFoundException("Position", String.valueOf(action.getPositionId())));
		}
		List<Position> matches = positions.findByTicker(action.getTicker());
		if (matches.size() != 1) {
			throw new BadRequestException(
					"No unique holding matches " + action.getTicker() + " to apply this action to");
		}
		return matches.get(0);
	}

	/** True if any OTHER position already holds {@code newTicker}. */
	private boolean targetHeldElsewhere(String newTicker, Long currentPositionId) {
		return positions.findByTicker(newTicker).stream()
				.anyMatch(p -> !p.getId().equals(currentPositionId));
	}

	private void requireTargetFree(String newTicker, Long currentPositionId) {
		if (targetHeldElsewhere(newTicker, currentPositionId)) {
			throw new BadRequestException("Target ticker " + newTicker
					+ " is already held; merging two holdings of the same security isn't supported yet");
		}
	}

	private static boolean involvesRename(CorporateActionType type) {
		return type == CorporateActionType.TICKER_CHANGE || type == CorporateActionType.MERGER;
	}

	/** Whether an action could be auto-applied if it matched exactly one position. */
	private static boolean isApplicable(CorporateActionType type, BigDecimal ratio, String newTicker) {
		return switch (type) {
			case SPLIT, REVERSE_SPLIT -> ratio != null && ratio.signum() > 0;
			case TICKER_CHANGE -> newTicker != null && !newTicker.isBlank();
			case STOCK_DIVIDEND, MERGER -> false; // always require manual confirmation
		};
	}

	private static String pendingReason(CorporateActionType type, int matchCount, boolean applicable,
			boolean targetConflict, String newTicker) {
		if (matchCount == 0) {
			return "No holding matches this ticker";
		}
		if (matchCount > 1) {
			return "Multiple holdings match this ticker — confirm which to adjust";
		}
		if (targetConflict) {
			return "Target ticker " + newTicker + " is already held — merge manually";
		}
		if (type == CorporateActionType.MERGER) {
			return "Mergers require manual confirmation";
		}
		if (type == CorporateActionType.STOCK_DIVIDEND) {
			return "Stock dividends require manual confirmation";
		}
		return applicable ? "Requires manual confirmation" : "Missing ratio / new ticker — confirm details";
	}

	private static BigDecimal requirePositiveRatio(BigDecimal ratio) {
		if (ratio == null || ratio.signum() <= 0) {
			throw new BadRequestException("A positive split ratio is required");
		}
		return ratio;
	}

	private static String requireNewTicker(String newTicker) {
		if (newTicker == null || newTicker.isBlank()) {
			throw new BadRequestException("A new ticker is required for a ticker change");
		}
		return newTicker;
	}
}
