package com.argus.recommendation;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 5's trust graduation state machine (Story 6.6, FR-11). Each recorded outcome re-evaluates the
 * state from the explicit rules: SHADOW promotes to PROBATION at ≥20 trades and ≥70% win rate;
 * PROBATION promotes to ACTIVE with sustained performance (≥40 trades, ≥60%); ACTIVE demotes to
 * PROBATION if the rolling last-10 win rate falls below 50%; and any state freezes on a serious-
 * failure pattern (rolling last-10 below 30%). FROZEN is terminal until manually reviewed.
 */
@Service
public class GraduationService {

	static final int PROMOTE_TRADES = 20;
	static final double PROMOTE_WIN_RATE = 0.70;
	static final int ACTIVE_TRADES = 40;
	static final double ACTIVE_WIN_RATE = 0.60;
	static final double DEMOTE_ROLLING_RATE = 0.50;
	static final double FREEZE_ROLLING_RATE = 0.30;
	private static final int ROLLING_WINDOW = 10;

	private static final Logger log = LoggerFactory.getLogger(GraduationService.class);

	private final AgentGraduationRepository graduation;
	private final PaperTradeRepository trades;

	public GraduationService(AgentGraduationRepository graduation, PaperTradeRepository trades) {
		this.graduation = graduation;
		this.trades = trades;
	}

	@Transactional(readOnly = true)
	public GraduationState currentState() {
		return graduation.findById(AgentGraduation.SINGLETON_ID)
				.map(AgentGraduation::getState).orElse(GraduationState.SHADOW);
	}

	/** Record a recommendation outcome and re-evaluate the state. Returns the (possibly new) state. */
	@Transactional
	public GraduationState recordOutcome(boolean won, Long recommendationId) {
		trades.save(new PaperTrade(won, recommendationId));

		int total = (int) trades.count();
		int wins = (int) trades.countByWonTrue();
		List<PaperTrade> last = trades.findTop10ByOrderByIdDesc();
		int rollingWins = (int) last.stream().filter(PaperTrade::isWon).count();

		AgentGraduation g = graduation.findById(AgentGraduation.SINGLETON_ID).orElseGet(AgentGraduation::new);
		GraduationState next = evaluate(g.getState(), total, wins, rollingWins, last.size());
		if (next != g.getState()) {
			log.info("Agent 5 graduation: {} -> {} ({} trades, {}% overall)",
					g.getState(), next, total, total == 0 ? 0 : Math.round(100.0 * wins / total));
			g.setState(next);
			graduation.save(g);
		}
		return next;
	}

	/** Pure transition rule (visible for testing). */
	static GraduationState evaluate(GraduationState current, int total, int wins, int rollingWins,
			int rollingCount) {
		if (current == GraduationState.FROZEN) {
			return GraduationState.FROZEN;
		}
		double overall = total == 0 ? 0 : (double) wins / total;
		boolean haveRolling = rollingCount >= ROLLING_WINDOW;
		double rolling = rollingCount == 0 ? 0 : (double) rollingWins / rollingCount;

		if (haveRolling && rolling < FREEZE_ROLLING_RATE) {
			return GraduationState.FROZEN; // serious-failure pattern
		}
		return switch (current) {
			case SHADOW -> (total >= PROMOTE_TRADES && overall >= PROMOTE_WIN_RATE)
					? GraduationState.PROBATION : GraduationState.SHADOW;
			case PROBATION -> (total >= ACTIVE_TRADES && overall >= ACTIVE_WIN_RATE)
					? GraduationState.ACTIVE : GraduationState.PROBATION;
			case ACTIVE -> (haveRolling && rolling < DEMOTE_ROLLING_RATE)
					? GraduationState.PROBATION : GraduationState.ACTIVE;
			case FROZEN -> GraduationState.FROZEN;
		};
	}
}
