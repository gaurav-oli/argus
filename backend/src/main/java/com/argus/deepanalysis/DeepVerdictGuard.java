package com.argus.deepanalysis;

import com.argus.learning.LessonEffect;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic guardrails over the LLM's final verdict — "the LLM proposes, the data disposes". The
 * portfolio-manager model may write a persuasive case for buying, but it cannot buy against the evidence:
 * this class can only <em>downgrade</em> a verdict (WORTH_BUYING → WAIT, NOT_WORTH_BUYING → WAIT) or
 * <em>cap</em> its conviction and holding period. It never upgrades a WAIT, and never invents a view.
 *
 * <p>Rules (each adds a plain-English note that is stored and shown):
 * <ul>
 *   <li><b>Consensus.</b> The specialists' stances form a weighted consensus in [-1, 1], reduced by the
 *       skeptic's severity. Buying needs consensus ≥ {@value #BUY_CONSENSUS}; "not worth buying" needs ≤ -{@value #AVOID_CONSENSUS}.</li>
 *   <li><b>Split desk.</b> A buy is downgraded when two or more specialists are strongly bearish.</li>
 *   <li><b>Speculative.</b> Sub-$5 stocks are never called worth buying.</li>
 *   <li><b>Event risk.</b> Earnings within 3 trading days force WAIT; within 10 trading days cap conviction and holding period.</li>
 *   <li><b>Conviction is anchored to the data:</b> capped at 40 + 60 × min(1, |consensus| / 0.6), and lower still
 *       for ETFs, missing fundamentals or a missing chart.</li>
 *   <li><b>Holding period</b> is snapped to 7 / 30 / 90 days; a 90-day hold needs supportive fundamentals.</li>
 *   <li><b>Its own track record.</b> Once enough of Agent 11's past verdicts of a kind have matured, a hit rate under 45% (or a
 *       negative mean excess return) caps conviction at 55 — the analyst discounts itself when it has been wrong.</li>
 *   <li><b>Learned lessons</b> ({@link LessonEffect}, mined from Argus's own paper trades and validated on held-out data):
 *       a matching block rule downgrades a buy to WAIT, penalties and boosts move conviction, hold caps shorten the hold.</li>
 * </ul>
 */
public final class DeepVerdictGuard {

	static final double BUY_CONSENSUS = 0.15;
	static final double AVOID_CONSENSUS = 0.10;
	static final double STRONG_STANCE = 0.6;
	static final double PENNY_PRICE = 5.0;
	static final double SKEPTIC_WEIGHT = 0.2;

	private DeepVerdictGuard() {
	}

	public enum Stance { BULLISH, BEARISH, NEUTRAL }

	/**
	 * One specialist's view. {@code weight} 0 means the specialist had nothing to say (e.g. fundamentals for an
	 * ETF) and is excluded from the consensus rather than counted as a neutral vote.
	 */
	public record Specialist(String name, Stance stance, double strength, double weight) {
		double signed() {
			return stance == Stance.BULLISH ? strength : stance == Stance.BEARISH ? -strength : 0;
		}
	}

	/** The portfolio-manager model's proposal. */
	public record Draft(DeepVerdict verdict, Integer holdDays, int conviction) {
	}

	/**
	 * @param lessons     what Argus has learned from past trades in a situation like this one (null = none)
	 * @param trackRecord how Agent 11's own past verdicts of this kind actually did (null = too few matured to judge)
	 */
	public record Input(List<Specialist> specialists, double skepticSeverity, boolean fundamentalsApplicable, Double fundamentalScore,
			boolean isEtf, Double lastPrice, Integer earningsInTradingDays, boolean chartAvailable, LessonEffect lessons, TrackRecord trackRecord) {

		/** Legacy arity (no track record). */
		public Input(List<Specialist> specialists, double skepticSeverity, boolean fundamentalsApplicable, Double fundamentalScore, boolean isEtf,
				Double lastPrice, Integer earningsInTradingDays, boolean chartAvailable, LessonEffect lessons) {
			this(specialists, skepticSeverity, fundamentalsApplicable, fundamentalScore, isEtf, lastPrice, earningsInTradingDays, chartAvailable, lessons, null);
		}
	}

	public record Result(DeepVerdict verdict, Integer holdDays, int conviction, double consensus, List<String> notes) {
	}

	/** Weighted stance consensus in [-1, 1], pulled toward zero by the skeptic's severity. */
	public static double consensus(List<Specialist> specialists, double skepticSeverity) {
		double w = 0, s = 0;
		for (Specialist sp : specialists) {
			if (sp.weight() <= 0) continue;
			w += sp.weight();
			s += sp.weight() * sp.signed();
		}
		if (w == 0) return 0;
		double c = s / w;
		double sev = Math.max(0, Math.min(1, skepticSeverity));
		double shrunk = Math.max(0, Math.abs(c) - SKEPTIC_WEIGHT * sev);
		return Math.signum(c) * shrunk;
	}

	public static Result apply(Draft draft, Input in) {
		List<String> notes = new ArrayList<>();
		double consensus = consensus(in.specialists(), in.skepticSeverity());
		DeepVerdict verdict = draft.verdict();
		Integer hold = draft.holdDays();
		int conviction = Math.max(0, Math.min(100, draft.conviction()));
		boolean downgraded = false;

		if (verdict == DeepVerdict.WORTH_BUYING) {
			long strongBears = in.specialists().stream().filter(s -> s.weight() > 0 && s.stance() == Stance.BEARISH
					&& s.strength() >= STRONG_STANCE).count();
			if (consensus < BUY_CONSENSUS) {
				verdict = DeepVerdict.WAIT;
				notes.add(String.format(Locale.ROOT, "Downgraded to WAIT: the analysts' consensus (%+.2f) is below the %+.2f needed to call a buy.",
						consensus, BUY_CONSENSUS));
			}
			else if (strongBears >= 2) {
				verdict = DeepVerdict.WAIT;
				notes.add("Downgraded to WAIT: two or more specialists are strongly bearish — the desk is split.");
			}
			else if (in.lastPrice() != null && in.lastPrice() < PENNY_PRICE) {
				verdict = DeepVerdict.WAIT;
				notes.add(String.format(Locale.ROOT, "Downgraded to WAIT: a sub-$%.0f stock (%.2f) is too speculative to call worth buying.",
						PENNY_PRICE, in.lastPrice()));
			}
		}
		else if (verdict == DeepVerdict.NOT_WORTH_BUYING && consensus > -AVOID_CONSENSUS) {
			verdict = DeepVerdict.WAIT;
			notes.add(String.format(Locale.ROOT, "Softened to WAIT: the analysts' consensus (%+.2f) does not support a firm 'avoid'.", consensus));
		}
		if (verdict != draft.verdict()) {
			downgraded = true;
		}

		LessonEffect fx = in.lessons() == null ? LessonEffect.none() : in.lessons();
		if (verdict == DeepVerdict.WORTH_BUYING && fx.blockReason() != null) {
			verdict = DeepVerdict.WAIT;
			downgraded = true;
			notes.add("Downgraded to WAIT: a lesson learned from past trades blocks buying this kind of setup — " + fx.blockReason());
		}
		if (verdict == DeepVerdict.WORTH_BUYING && fx.scoreDelta() != 0) {
			conviction += fx.scoreDelta();
			for (LessonEffect.Applied a : fx.applied()) {
				if (a.kind().equals("PENALTY") || a.kind().equals("BOOST")) {
					notes.add(String.format(Locale.ROOT, "Lesson applied (%s%.0f conviction): %s", a.kind().equals("BOOST") ? "+" : "−", a.effect(), a.description()));
				}
			}
			conviction = Math.max(0, Math.min(100, conviction));
		}

		Integer earn = in.earningsInTradingDays();
		if (verdict == DeepVerdict.WORTH_BUYING && earn != null && earn <= 3) {
			verdict = DeepVerdict.WAIT;
			downgraded = true;
			notes.add("Downgraded to WAIT: earnings are " + earn + " trading day(s) away — a binary event that can swamp the thesis.");
		}
		else if (verdict == DeepVerdict.WORTH_BUYING && earn != null && earn <= 10) {
			conviction -= 10;
			notes.add("Conviction reduced 10 points: earnings are " + earn + " trading days away.");
		}

		// Conviction can never exceed what the analysts' consensus supports.
		int dataCap = (int) Math.round(40 + 60 * Math.min(1.0, Math.abs(consensus) / 0.6));
		if (conviction > dataCap && verdict != DeepVerdict.WAIT) {
			conviction = dataCap;
			notes.add("Conviction capped at " + dataCap + ": that is all the analysts' consensus supports.");
		}
		if (in.isEtf && conviction > 70) {
			conviction = 70;
			notes.add("Conviction capped at 70: an ETF has no company fundamentals to analyse.");
		}
		else if (!in.isEtf && !in.fundamentalsApplicable && conviction > 65) {
			conviction = 65;
			notes.add("Conviction capped at 65: no fundamental data was available for this company.");
		}
		TrackRecord tr = in.trackRecord();
		if (tr != null && verdict != DeepVerdict.WAIT && tr.n() >= DeepScorecardService.MIN_MATURED_FOR_TRACK_RECORD
				&& (tr.hitRate() < 0.45 || tr.meanExcessPct() < -1.0) && conviction > 55) {
			conviction = 55;
			notes.add(String.format(Locale.ROOT, "Conviction capped at 55: Agent 11's own past %s verdicts beat/lagged the S&P as called only %.0f%% of the time over %d verdicts (%d-day, average %+.1f%%).",
					verdict == DeepVerdict.WORTH_BUYING ? "'worth buying'" : "'not worth buying'", tr.hitRate() * 100, tr.n(), tr.horizonDays(), tr.meanExcessPct()));
		}
		if (!in.chartAvailable && conviction > 60) {
			conviction = 60;
			notes.add("Conviction capped at 60: there was not enough price history to read the chart.");
		}
		if (downgraded) {
			conviction = Math.min(conviction, 50);
		}

		// Holding period: only a buy has one.
		if (verdict == DeepVerdict.WORTH_BUYING) {
			int h = snapHold(hold);
			if (hold == null) {
				notes.add("The model gave no holding period; defaulted to 30 days.");
			}
			if (h == 90 && (!in.fundamentalsApplicable || in.fundamentalScore() == null || in.fundamentalScore() < 0.1)) {
				h = 30;
				notes.add("Holding period shortened from 90 to 30 days: a long hold needs supportive fundamentals, which were not there.");
			}
			if (h == 90 && earn != null && earn <= 10) {
				h = 30;
			}
			if (fx.holdCapDays() != null && h > fx.holdCapDays()) {
				h = fx.holdCapDays() < 30 ? 7 : 30;
				notes.add("Holding period capped at " + h + " days by a lesson learned from past trades.");
			}
			hold = h;
		}
		else {
			hold = null;
		}
		return new Result(verdict, hold, conviction, consensus, List.copyOf(notes));
	}

	/** Snap to the nearest of 7 / 30 / 90 days in log space (geometric midpoints 14.5 and 52); null → 30. */
	static int snapHold(Integer days) {
		if (days == null || days <= 0) return 30;
		return days <= 14 ? 7 : days <= 52 ? 30 : 90;
	}
}
