package com.argus.recommendation;

import java.time.Duration;
import java.time.Instant;

/**
 * How the paper investor manages an open position between entry and horizon. Pure: given the position,
 * the live price and Agent 10's read, it decides the new stop and whether to exit or take half off.
 *
 * <ul>
 *   <li><b>Hard stop</b> — price through the stop exits at once, at the live price (a gap through the
 *       stop fills at the worse price, never the stop's). A stop tightened since entry exits as a
 *       TRAILING_STOP.</li>
 *   <li><b>Breakeven, then trail</b> — once the best price since entry is {@value #BREAKEVEN_ATRS} ATR in
 *       the money, the stop moves to entry; from there it trails {@value #TRAIL_ATRS} ATR behind the best
 *       price, or just beyond the nearest live support/resistance when that is tighter (but at least
 *       {@value #MIN_LEVEL_GAP_ATRS} ATR from price, so ordinary noise doesn't shake the position out).</li>
 *   <li><b>Take profit</b> — a swing call's target takes half off; the rest trails from at least breakeven.
 *       A core hold has no target and only trails.</li>
 *   <li><b>Earnings</b> — within the quiet period the stop tightens to {@value #EARNINGS_ATRS} ATR from price.</li>
 *   <li><b>Never loosened</b> — a stop only ever moves in the position's favour, and never to within
 *       {@value #MIN_STOP_GAP_ATRS} ATR of price (that would just be an exit).</li>
 *   <li><b>Minimum hold</b> — everything except the hard stop waits {@link #MIN_HOLD} after entry.</li>
 * </ul>
 */
public final class PositionRules {

	static final double BREAKEVEN_ATRS = 1.0;
	static final double TRAIL_ATRS = 2.5;
	static final double EARNINGS_ATRS = 1.0;
	static final double MIN_LEVEL_GAP_ATRS = 1.0;
	static final double LEVEL_BUFFER_ATRS = 0.5;
	static final double MIN_STOP_GAP_ATRS = 0.25;
	/** Used when the chart has no ATR yet: a typical daily range. */
	static final double DEFAULT_ATR_PCT = 3.0;
	static final Duration MIN_HOLD = Duration.ofHours(24);

	private PositionRules() {
	}

	public enum Action { HOLD, EXIT, TAKE_HALF }

	/** The current state of an open position. {@code stop}, {@code highWater} and {@code target} may be null. */
	public record Position(boolean bullish, double entry, Double stop, Double highWater, Double target, boolean scaledOut,
			boolean stopTrailed, Instant entryAt) {
	}

	/** What Agent 10 and the calendar say right now. Any field may be null/false when unknown. */
	public record Market(double price, Double atrPct, Double support, Double resistance, boolean earningsSoon) {
	}

	/** {@code exitReason} is set for EXIT. {@code stop}/{@code highWater} are the values to persist either way. */
	public record Decision(Action action, String exitReason, Double stop, Double highWater) {
	}

	/** The rules' view of a stored trade. */
	static Position of(SimulatedTrade t) {
		return new Position(t.getDirection() == SignalDirection.BULLISH, t.getEntryPrice().doubleValue(),
				t.getStopPrice() == null ? null : t.getStopPrice().doubleValue(),
				t.getHighWater() == null ? null : t.getHighWater().doubleValue(),
				t.getTargetPrice() == null ? null : t.getTargetPrice().doubleValue(), t.isScaledOut(), t.isStopTrailed(),
				t.getEntryAt());
	}

	public static Decision evaluate(Position p, Market m, Instant now) {
		double price = m.price();
		double atr = (m.atrPct() == null || m.atrPct() <= 0 ? DEFAULT_ATR_PCT : m.atrPct()) / 100.0;
		double previous = p.highWater() == null ? p.entry() : p.highWater();
		double hw = p.bullish() ? Math.max(previous, price) : Math.min(previous, price);

		// 1. Hard stop: any time, no minimum hold.
		if (p.stop() != null && (p.bullish() ? price <= p.stop() : price >= p.stop())) {
			return new Decision(Action.EXIT, p.stopTrailed() ? "TRAILING_STOP" : "STOP", p.stop(), hw);
		}
		boolean held = !now.isBefore(p.entryAt().plus(MIN_HOLD));
		if (!held) {
			return new Decision(Action.HOLD, null, p.stop(), hw);
		}

		// 2. Take half off at the target (once).
		if (p.target() != null && !p.scaledOut() && (p.bullish() ? price >= p.target() : price <= p.target())) {
			Double stop = tighten(p.bullish(), p.stop(), p.entry(), price, atr); // the rest trails from at least breakeven
			return new Decision(Action.TAKE_HALF, null, stop, hw);
		}

		// 3. Ratchet the stop.
		Double stop = p.stop();
		double favourable = p.bullish() ? hw / p.entry() - 1 : 1 - hw / p.entry();
		if (favourable >= BREAKEVEN_ATRS * atr || p.scaledOut()) {
			stop = tighten(p.bullish(), stop, p.entry(), price, atr);
			double trail = p.bullish() ? hw * (1 - TRAIL_ATRS * atr) : hw * (1 + TRAIL_ATRS * atr);
			stop = tighten(p.bullish(), stop, trail, price, atr);
			Double level = p.bullish() ? m.support() : m.resistance();
			if (level != null && (p.bullish() ? level <= price * (1 - MIN_LEVEL_GAP_ATRS * atr)
					: level >= price * (1 + MIN_LEVEL_GAP_ATRS * atr))) {
				double beyond = p.bullish() ? level * (1 - LEVEL_BUFFER_ATRS * atr) : level * (1 + LEVEL_BUFFER_ATRS * atr);
				stop = tighten(p.bullish(), stop, beyond, price, atr);
			}
		}
		if (m.earningsSoon()) {
			double tight = p.bullish() ? price * (1 - EARNINGS_ATRS * atr) : price * (1 + EARNINGS_ATRS * atr);
			stop = tighten(p.bullish(), stop, tight, price, atr);
		}
		return new Decision(Action.HOLD, null, stop, hw);
	}

	/**
	 * Agent 5's latest call no longer supports the position: the opposite direction exits (after the minimum
	 * hold); a mere WATCH tightens the stop to {@value #EARNINGS_ATRS} ATR instead of forcing a sale.
	 */
	public static Decision onNewCall(Position p, boolean callIsOpposite, boolean callIsWatch, double price, Double atrPct,
			Instant now) {
		double atr = (atrPct == null || atrPct <= 0 ? DEFAULT_ATR_PCT : atrPct) / 100.0;
		if (now.isBefore(p.entryAt().plus(MIN_HOLD))) {
			return new Decision(Action.HOLD, null, p.stop(), p.highWater());
		}
		if (callIsOpposite) {
			return new Decision(Action.EXIT, "THESIS_DECAY", p.stop(), p.highWater());
		}
		if (callIsWatch) {
			double tight = p.bullish() ? price * (1 - EARNINGS_ATRS * atr) : price * (1 + EARNINGS_ATRS * atr);
			return new Decision(Action.HOLD, null, tighten(p.bullish(), p.stop(), tight, price, atr), p.highWater());
		}
		return new Decision(Action.HOLD, null, p.stop(), p.highWater());
	}

	/** {@code candidate} if it is tighter than {@code current} and not within the minimum gap of price; else {@code current}. */
	static Double tighten(boolean bullish, Double current, double candidate, double price, double atr) {
		double cap = bullish ? price * (1 - MIN_STOP_GAP_ATRS * atr) : price * (1 + MIN_STOP_GAP_ATRS * atr);
		double c = bullish ? Math.min(candidate, cap) : Math.max(candidate, cap);
		if (current == null) {
			return c;
		}
		return bullish ? Math.max(current, c) : Math.min(current, c);
	}
}
