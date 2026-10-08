package com.argus.recommendation;

import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
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
 *
 * <p><b>2026-09-02 incident:</b> a real freeze (2/10 rolling win rate) sat unnoticed for two weeks —
 * {@code RecommendationTrigger} correctly stopped producing new recommendations, but nothing told
 * the user it had happened; the only surface was a small badge on the Recommendation Cards page.
 * FROZEN now also pushes a CRITICAL alert (see {@link #recordOutcome}), the same pattern already
 * used for Stranger Danger / breaking news / lockout alerts, so a freeze is noticed within minutes
 * instead of discovered two weeks later via a stale-looking win rate.
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
	private final NotificationService notifications;

	public GraduationService(AgentGraduationRepository graduation, PaperTradeRepository trades,
			NotificationService notifications) {
		this.graduation = graduation;
		this.trades = trades;
		this.notifications = notifications;
	}

	/** Agent 5's trust posture for the UI: state, badge, and the real track record behind it. */
	public record GraduationSummary(String state, String badge, boolean canRecommend, long trades,
			int winRatePct, long tradesToValidated) {
	}

	@Transactional(readOnly = true)
	public GraduationState currentState() {
		return graduation.findById(AgentGraduation.SINGLETON_ID)
				.map(AgentGraduation::getState).orElse(GraduationState.SHADOW);
	}

	/** State + track record, so the "UNPROVEN" badge can be shown with honest context. */
	@Transactional(readOnly = true)
	public GraduationSummary summary() {
		GraduationState state = currentState();
		long total = trades.count();
		long wins = trades.countByWonTrue();
		int pct = total == 0 ? 0 : (int) Math.round(100.0 * wins / total);
		return new GraduationSummary(state.name(), state.badge(), state.canRecommend(), total, pct,
				Math.max(0, ACTIVE_TRADES - total));
	}

	/**
	 * Manual review (Story 6.6): resume a FROZEN Agent 5 back to SHADOW — not straight to PROBATION
	 * or ACTIVE. A freeze means a real failure pattern happened; resuming should mean "prove it
	 * again from zero trust", same posture as every other cold-start in this app (e.g. Agent 3's
	 * re-admission), not "assume the fix worked". No-ops (returns the unchanged state) if not
	 * currently FROZEN, so this can't be used to skip the earn-your-way-up ladder outside a real freeze.
	 */
	@Transactional
	public GraduationState resume() {
		AgentGraduation g = graduation.findById(AgentGraduation.SINGLETON_ID).orElseGet(AgentGraduation::new);
		if (g.getState() != GraduationState.FROZEN) {
			return g.getState();
		}
		g.setState(GraduationState.SHADOW);
		graduation.save(g);
		log.info("Agent 5 graduation: FROZEN -> SHADOW (manual review — resumed)");
		return GraduationState.SHADOW;
	}

	/** Record a recommendation outcome and re-evaluate the state. Returns the (possibly new) state. */
	@Transactional
	public GraduationState recordOutcome(boolean won, Long recommendationId) {
		return recordOutcome(won, recommendationId, true);
	}

	/**
	 * Record an outcome; only one with {@code countsForGraduation} (a trade the current system opened) feeds the
	 * state machine. An old-system trade closing — e.g. the thesis-decay rule exiting a months-old losing leg —
	 * is recorded for the all-time record but can never promote, demote or freeze the current Agent 5.
	 */
	@Transactional
	public GraduationState recordOutcome(boolean won, Long recommendationId, boolean countsForGraduation) {
		trades.save(new PaperTrade(won, recommendationId, countsForGraduation));
		if (!countsForGraduation) {
			return currentState();
		}

		int total = (int) trades.countByCountsForGraduationTrue();
		int wins = (int) trades.countByWonTrueAndCountsForGraduationTrue();
		List<PaperTrade> last = trades.findTop10ByCountsForGraduationTrueOrderByIdDesc();
		int rollingWins = (int) last.stream().filter(PaperTrade::isWon).count();

		AgentGraduation g = graduation.findById(AgentGraduation.SINGLETON_ID).orElseGet(AgentGraduation::new);
		GraduationState previous = g.getState();
		GraduationState next = evaluate(previous, total, wins, rollingWins, last.size());
		if (next != previous) {
			log.info("Agent 5 graduation: {} -> {} ({} trades, {}% overall)",
					previous, next, total, total == 0 ? 0 : Math.round(100.0 * wins / total));
			g.setState(next);
			graduation.save(g);
			if (next == GraduationState.FROZEN) {
				alertFrozen(rollingWins, last.size());
			}
		}
		return next;
	}

	/** CRITICAL, non-ticker push (bypasses the fatigue gate + quiet hours, never dedups) — a freeze
	 * means Agent 5 has stopped producing ANY new recommendation until someone reviews and calls
	 * {@link #resume()}; that must be noticed immediately, not discovered later via a stale-looking
	 * win rate. Best-effort: a push failure must never mask the freeze itself. */
	private void alertFrozen(int rollingWins, int rollingCount) {
		try {
			notifications.notify(Notification.of(UrgencyTier.CRITICAL,
					"🧊 Agent 5 has frozen — no new recommendations",
					"Only " + rollingWins + "/" + rollingCount + " of the last " + rollingCount
							+ " calls won, so Agent 5 stopped recommending until reviewed. Check the "
							+ "recent losing trades, then resume it from the Recommendations page when ready.",
					"/recommendations"));
		} catch (RuntimeException ex) {
			log.warn("Agent 5 freeze alert failed: {}", ex.getMessage());
		}
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
