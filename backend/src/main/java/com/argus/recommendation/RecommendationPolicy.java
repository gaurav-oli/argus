package com.argus.recommendation;

import com.argus.deepanalysis.DeepVerdict;
import com.argus.deepanalysis.DeepView;
import com.argus.learning.FeatureTokens;
import com.argus.learning.LessonEffect;
import com.argus.learning.Lessons;
import com.argus.learning.SignalGroup;
import com.argus.regime.MacroTheme;
import com.argus.regime.MarketRegime;
import com.argus.regime.Sector;
import com.argus.technical.ChartStudy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns the scoring engine's raw probability into something a person can act on: an {@link
 * RecommendationAction}, a 0–100 <b>conviction score</b>, a <b>recommended holding period</b>
 * (short / medium / long), and a plain-English thesis. Deterministic and auditable like the engine
 * beneath it — no LLM produces any number here.
 *
 * <p>Why it exists: the engine always returns a bull/bear split, so a ticker with a single macro
 * headline or a lone weak signal still got a "BULLISH 52%" card and a paper trade. Roughly 80% of the
 * paper book was such no-edge calls. This layer adds the missing abstain option and refuses to call
 * a direction unless the evidence is broad, agreeing, and not contradicted by the tape.
 *
 * <p><b>Conviction score</b> (0–100) = 50% evidence strength (net signed weight, no single signal counting
 * for more than {@value #MAX_SIGNAL_CONTRIBUTION}) + 25% breadth (independent sources agreeing) + 25% agreement (little
 * opposition), then adjusted for known headwinds, for what the system has <b>learned from its own past
 * trades</b> ({@link Lessons}), and it is <em>not</em> inflated to look impressive: weak or lone signals score
 * low and become {@link RecommendationAction#WATCH}.
 *
 * <p><b>Agent 11 (deep analysis)</b> participates in three ways: its fresh verdict is a hard evidence group that
 * feeds the score; a verdict that contradicts the call vetoes it (a quick BUY against a deep "not worth buying"
 * becomes WATCH, and vice versa); and when it agrees on a buy its holding period — informed by fundamentals —
 * replaces the quick agents' estimate.
 */
@Component
public class RecommendationPolicy {

	/** Below this conviction Argus makes no call. */
	static final int ACTIONABLE_SCORE = 62;
	static final int STRONG_SCORE = 80;
	/** Net signed weight at which evidence strength saturates (≈ three solid, agreeing signals). */
	static final double FULL_STRENGTH_WEIGHT = 1.8;
	/**
	 * No single signal may contribute more than this to evidence strength. Agent 11's weight can reach 1.2
	 * (deliberately, so a confident deep verdict can outweigh bearish headlines in the probability) — but
	 * one LLM-derived verdict must not by itself saturate the conviction score: live, a single dip call
	 * once turned a weak +0.11 news read into 100/100.
	 */
	static final double MAX_SIGNAL_CONTRIBUTION = 0.9;
	static final double PENNY_PRICE = 5.0;
	/** A move this big today means the easy part is over — don't chase it. */
	static final double CHASE_MOVE_PCT = 7.0;
	static final double SINGLE_SOURCE_MIN_WEIGHT = 0.6;

	/** Holding-period buckets — deliberately the same 7/30/90 the paper investor trades. */
	static final int SHORT_DAYS = 7;
	static final int MEDIUM_DAYS = 30;
	static final int LONG_DAYS = 90;

	private final Lessons lessons;

	public RecommendationPolicy(Lessons lessons) {
		this.lessons = lessons;
	}

	/**
	 * Everything the policy needs, gathered by the caller.
	 *
	 * @param lastPrice   live price, null if unknown
	 * @param stockMove1d the stock's move today in %, null if unknown
	 * @param chart       Agent 10's chart study, null if there is too little history
	 * @param deep        Agent 11's latest fresh verdict, null if none
	 */
	public record Context(String ticker, ProbabilityScore score, List<AgentSignal> signals, Sector sector, MarketRegime regime,
			Double lastPrice, Double stockMove1d, boolean earningsSoon, ChartStudy chart, DeepView deep) {
	}

	/**
	 * The decision, ready to persist and render.
	 *
	 * @param features the situation this call was made in, as tokens — stored so the Trade Learner can later
	 *                 mine outcomes against the conditions that produced them
	 * @param learned  the learned lessons that shaped this call, one line each
	 */
	public record Verdict(RecommendationAction action, int score, int holdDays, String horizonLabel, String thesis,
			List<String> reasons, List<String> caveats, String exitPlan, Set<String> features, List<String> learned) {
	}

	public Verdict evaluate(Context ctx) {
		ProbabilityScore ps = ctx.score();
		SignalDirection dir = ps.bullProbability() >= 0.5 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		SignalDirection opposite = dir == SignalDirection.BULLISH ? SignalDirection.BEARISH : SignalDirection.BULLISH;
		MarketRegime regime = ctx.regime() == null ? MarketRegime.unavailable() : ctx.regime();

		List<AgentSignal> supports = new ArrayList<>();
		List<AgentSignal> opposes = new ArrayList<>();
		for (AgentSignal s : ctx.signals()) {
			if (SignalGroup.of(s.agent()) == SignalGroup.CALENDAR || s.weight() <= 0) continue;
			if (s.direction() == dir) supports.add(s);
			else if (s.direction() == opposite) opposes.add(s);
		}
		Set<SignalGroup> supportGroups = groupsOf(supports);
		Set<SignalGroup> opposeGroups = groupsOf(opposes);
		Set<SignalGroup> independent = EnumSet.noneOf(SignalGroup.class);
		supportGroups.stream().filter(g -> g != SignalGroup.MACRO).forEach(independent::add);
		Set<SignalGroup> hardSupport = EnumSet.noneOf(SignalGroup.class);
		supportGroups.stream().filter(SignalGroup::hard).forEach(hardSupport::add);
		long hardOppose = opposeGroups.stream().filter(SignalGroup::hard).count();

		// ---- conviction score ----
		double net = 0;
		double directional = 0;
		for (AgentSignal s : ctx.signals()) {
			if (SignalGroup.of(s.agent()) == SignalGroup.CALENDAR || s.weight() <= 0 || s.direction() == SignalDirection.NEUTRAL) continue;
			double w = Math.min(s.weight(), MAX_SIGNAL_CONTRIBUTION);
			net += s.direction().sign() * w;
			directional += w;
		}
		net = Math.abs(net);
		double strength = Math.min(1.0, net / FULL_STRENGTH_WEIGHT);
		double breadth = Math.min(1.0, (independent.size() + (supportGroups.contains(SignalGroup.MACRO) ? 0.5 : 0)) / 3.0);
		double agreement = directional == 0 ? 0 : net / directional;
		double raw = 100.0 * (0.5 * strength + 0.25 * breadth + 0.25 * agreement);

		List<String> caveats = new ArrayList<>();
		if (hardOppose > 0) {
			raw -= Math.min(20, 10 * hardOppose);
			opposes.stream().filter(s -> SignalGroup.of(s.agent()).hard()).forEach(s -> caveats.add(
					"Disagreement: " + SignalGroup.of(s.agent()).label() + " leans " + s.direction().name().toLowerCase()
							+ " — " + trim(s.rationale())));
		}
		boolean dipBuy = supportGroups.contains(SignalGroup.DEEP);
		if (dir == SignalDirection.BULLISH && (regime.riskOff() || regime.broadSelloff()) && !dipBuy) {
			raw -= 10;
			caveats.add("Market headwind: " + regime.summary() + " — buying into weakness without a dip thesis.");
		}
		if (dir == SignalDirection.BEARISH && regime.riskOn()) {
			raw -= 8;
			caveats.add("Market tailwind: " + regime.summary() + " — fighting a rising tape.");
		}
		if (dir == SignalDirection.BULLISH && regime.ratesRising()
				&& MacroTheme.RATES_YIELDS.sensitivity(ctx.sector()) >= 0.8) {
			raw -= 5;
			caveats.add("Rate headwind: yields are rising and " + ctx.sector().label().toLowerCase()
					+ " is rate-sensitive.");
		}
		if (ctx.earningsSoon()) {
			raw -= 10;
			caveats.add("Earnings are close — results can swamp everything else; consider sizing down.");
		}
		boolean penny = ctx.lastPrice() != null && ctx.lastPrice() < PENNY_PRICE;
		if (penny) {
			raw -= 25;
		}
		int baseScore = (int) Math.max(0, Math.min(100, Math.round(raw)));

		// ---- hold period: the quick agents' estimate, replaced by Agent 11's when it agrees on a buy ----
		int holdDays = holdDays(supports, ctx.earningsSoon());
		DeepView deep = ctx.deep();
		if (deep != null && deep.verdict() == DeepVerdict.WORTH_BUYING && dir == SignalDirection.BULLISH && deep.holdDays() != null
				&& !ctx.earningsSoon()) {
			holdDays = snapHold(deep.holdDays());
		}

		// ---- what the system has learned from its own past trades ----
		Set<String> features = features(ctx, dir, supports, supportGroups, baseScore, holdDays, regime, deep);
		LessonEffect fx = lessons.evaluate(features);
		List<String> learned = new ArrayList<>();
		for (LessonEffect.Applied a : fx.applied()) {
			learned.add(learnedLine(a));
		}
		int score = Math.max(0, Math.min(100, baseScore + fx.scoreDelta()));
		if (fx.holdCapDays() != null && holdDays > fx.holdCapDays()) {
			holdDays = fx.holdCapDays() < MEDIUM_DAYS ? SHORT_DAYS : MEDIUM_DAYS;
		}
		// "Strong" needs breadth that doesn't lean on an LLM verdict: ≥3 independent sources, of which ≥2 are hard
		// and not Agent 11 (whose verdict has no long track record yet).
		long solidHard = hardSupport.stream().filter(g -> g != SignalGroup.DEEP).count();
		if (independent.size() < 3 || solidHard < 2) {
			score = Math.min(score, STRONG_SCORE - 1);
		}

		// ---- abstain rules (each gives the user a reason, not silence) ----
		List<String> whyNot = new ArrayList<>();
		AgentSignal strongestHard = supports.stream().filter(s -> SignalGroup.of(s.agent()).hard())
				.max(Comparator.comparingDouble(AgentSignal::weight)).orElse(null);
		boolean singleStrongSource = independent.size() == 1 && strongestHard != null
				&& strongestHard.weight() >= SINGLE_SOURCE_MIN_WEIGHT && hardOppose == 0;
		if (hardSupport.isEmpty()) {
			whyNot.add("no hard evidence (company news, insider activity, the chart, fundamentals or a deep analysis) — only "
					+ (supportGroups.isEmpty() ? "no signals" : describe(supportGroups)));
		}
		else if (independent.size() < 2 && !singleStrongSource) {
			whyNot.add("only " + independent.size() + " independent source(s) agree; need 2 (or one very strong one)");
		}
		if (penny) {
			whyNot.add(String.format(Locale.ROOT, "speculative sub-$%.0f stock (%.2f) — too noisy to call", PENNY_PRICE,
					ctx.lastPrice()));
		}
		if (dir == SignalDirection.BEARISH && regime.broadSelloff()
				&& !supportGroups.contains(SignalGroup.INSIDER) && !supportGroups.contains(SignalGroup.TECHNICAL)) {
			whyNot.add("market-wide selloff (" + regime.summary() + ") — shock headlines often reverse within days,"
					+ " so Argus won't sell into it on headlines alone");
		}
		Double move = ctx.stockMove1d();
		if (move != null && (dir == SignalDirection.BULLISH && move >= CHASE_MOVE_PCT
				|| dir == SignalDirection.BEARISH && move <= -CHASE_MOVE_PCT)) {
			whyNot.add(String.format(Locale.ROOT, "already moved %+.1f%% today — chasing it is where the edge is usually gone",
					move));
		}
		if (deep != null) {
			String headline = deep.headline() == null || deep.headline().isBlank() ? "" : ": " + deep.headline();
			if (dir == SignalDirection.BULLISH && deep.verdict() == DeepVerdict.NOT_WORTH_BUYING) {
				whyNot.add("Agent 11's deep analysis says this is not worth buying" + headline);
			}
			else if (dir == SignalDirection.BEARISH && deep.verdict() == DeepVerdict.WORTH_BUYING) {
				whyNot.add("Agent 11's deep analysis says this is worth buying, contradicting the sell signals" + headline);
			}
			else if (deep.verdict() == DeepVerdict.WAIT) {
				caveats.add("Agent 11's deep analysis says wait" + headline);
			}
		}
		if (fx.blockReason() != null) {
			whyNot.add("a lesson learned from past trades blocks it — " + fx.blockReason());
		}
		if (score < ACTIONABLE_SCORE) {
			whyNot.add("conviction " + score + "/100 is below the " + ACTIONABLE_SCORE + " needed to act");
		}

		if (!whyNot.isEmpty()) {
			return new Verdict(RecommendationAction.WATCH, score, holdDays, horizonLabel(holdDays),
					"No clear edge on " + ctx.ticker() + ": " + String.join("; ", whyNot) + ".", List.of(), caveats,
					"Nothing to do — Argus will re-check on the next review.", features, learned);
		}

		RecommendationAction action = dir == SignalDirection.BULLISH
				? (score >= STRONG_SCORE ? RecommendationAction.STRONG_BUY : RecommendationAction.BUY)
				: (score >= STRONG_SCORE ? RecommendationAction.STRONG_AVOID : RecommendationAction.AVOID);

		List<String> reasons = supports.stream().sorted(Comparator.comparingDouble(AgentSignal::weight).reversed())
				.limit(4).map(s -> cap(SignalGroup.of(s.agent()).label()) + ": " + trim(s.rationale())).toList();
		if (penny) {
			caveats.add("Low-priced stock — expect large swings.");
		}
		String thesis = String.format(Locale.ROOT, "%s %s — %d independent signal%s agree (%s). Hold about %d days (%s).",
				action.label(), ctx.ticker(), Math.max(1, independent.size()), independent.size() > 1 ? "s" : "",
				describe(supportGroups), holdDays, horizonLabel(holdDays).toLowerCase(Locale.ROOT));
		String invalidation = deep != null && deep.invalidation() != null && !deep.invalidation().isBlank()
				? " Agent 11 would change its mind if: " + deep.invalidation() : "";
		return new Verdict(action, score, holdDays, horizonLabel(holdDays), thesis, reasons, caveats,
				exitPlan(dir, hardSupport, holdDays) + invalidation, features, learned);
	}

	/**
	 * The situation this call is being made in, as tokens ({@link FeatureTokens}): direction, sector, holding
	 * period, conviction bucket, market regime, chart trend and bias, the deep verdict, which evidence groups
	 * support it and which leads, price and volatility buckets, and earnings proximity. This is what lessons
	 * match against and what the learner later mines outcomes by.
	 */
	private Set<String> features(Context ctx, SignalDirection dir, List<AgentSignal> supports, Set<SignalGroup> supportGroups,
			int baseScore, int holdDays, MarketRegime regime, DeepView deep) {
		Set<String> t = new LinkedHashSet<>();
		t.add("dir=" + dir.name());
		t.add("sector=" + ctx.sector().name());
		t.add(FeatureTokens.horizonToken(holdDays));
		t.add("conv=" + FeatureTokens.convictionBucket(baseScore));
		t.add("ticker=" + ctx.ticker());
		if (regime.available()) {
			t.add("regime=" + regime.label());
		}
		if (ctx.chart() != null) {
			t.add("trend=" + ctx.chart().trend().name());
			t.add("chart=" + ctx.chart().bias());
			FeatureTokens.addIfPresent(t, "vol", FeatureTokens.volatilityBucket(ctx.chart().atrPct()));
		}
		t.add("deep=" + (deep == null ? "NONE" : deep.verdict().name()));
		FeatureTokens.addIfPresent(t, "price", FeatureTokens.priceBucket(ctx.lastPrice()));
		if (ctx.earningsSoon()) {
			t.add("earnings=soon");
		}
		Map<SignalGroup, Double> weights = new EnumMap<>(SignalGroup.class);
		for (AgentSignal s : supports) {
			weights.merge(SignalGroup.of(s.agent()), Math.min(s.weight(), MAX_SIGNAL_CONTRIBUTION), Double::sum);
		}
		t.addAll(FeatureTokens.groupTokens(weights));
		return t;
	}

	private static String learnedLine(LessonEffect.Applied a) {
		String head = switch (a.kind()) {
			case "PENALTY" -> String.format(Locale.ROOT, "−%.0f conviction", a.effect());
			case "BOOST" -> String.format(Locale.ROOT, "+%.0f conviction", a.effect());
			case "BLOCK" -> "Blocked";
			case "CAP_HOLD" -> String.format(Locale.ROOT, "Hold capped at %.0f days", a.effect());
			default -> String.format(Locale.ROOT, "Position size ×%.2f", a.effect());
		};
		return head + ": " + a.description() + " [" + a.stats() + "]";
	}

	/** Weighted geometric mean of each supporting signal's evidence half-life, snapped to 7 / 30 / 90 days. */
	static int holdDays(List<AgentSignal> supports, boolean earningsSoon) {
		double wSum = 0;
		double logSum = 0;
		for (AgentSignal s : supports) {
			int hint = s.horizonHintDays() == null ? 10 : Math.max(1, s.horizonHintDays());
			wSum += s.weight();
			logSum += s.weight() * Math.log(hint);
		}
		double days = wSum == 0 ? 10 : Math.exp(logSum / wSum);
		return earningsSoon ? SHORT_DAYS : snapHold((int) Math.round(days));
	}

	/** Bucket edges are the geometric midpoints between 7, 30 and 90 (√210 ≈ 14.5, √2700 ≈ 52). */
	static int snapHold(int days) {
		return days <= 14 ? SHORT_DAYS : days <= 52 ? MEDIUM_DAYS : LONG_DAYS;
	}

	static String horizonLabel(int holdDays) {
		return holdDays <= SHORT_DAYS ? "Short-term · about 1 week"
				: holdDays <= MEDIUM_DAYS ? "Medium-term · about 1 month" : "Long-term · about 3 months";
	}

	private static String exitPlan(SignalDirection dir, Set<SignalGroup> hard, int holdDays) {
		List<String> triggers = new ArrayList<>();
		if (hard.contains(SignalGroup.NEWS)) {
			triggers.add(dir == SignalDirection.BULLISH ? "company news turns negative" : "company news turns positive");
		}
		if (hard.contains(SignalGroup.INSIDER)) {
			triggers.add(dir == SignalDirection.BULLISH ? "insiders start selling" : "insiders start buying");
		}
		if (hard.contains(SignalGroup.TECHNICAL) || hard.contains(SignalGroup.DEEP)) {
			triggers.add("the chart or Agent 11's read flips");
		}
		if (hard.contains(SignalGroup.FUNDAMENTAL)) {
			triggers.add("the fundamentals deteriorate");
		}
		triggers.add("the market turns sharply against it");
		return "Re-check after " + holdDays + " days, or sooner if " + String.join(", ", triggers) + ".";
	}

	private static Set<SignalGroup> groupsOf(List<AgentSignal> signals) {
		Set<SignalGroup> g = EnumSet.noneOf(SignalGroup.class);
		signals.forEach(s -> g.add(SignalGroup.of(s.agent())));
		return g;
	}

	private static String describe(Set<SignalGroup> groups) {
		return groups.stream().map(SignalGroup::label).reduce((a, b) -> a + ", " + b).orElse("none");
	}

	private static String trim(String s) {
		if (s == null) return "";
		return s.length() <= 140 ? s : s.substring(0, 137) + "…";
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
