package com.argus.recommendation;

import com.argus.recommendation.TradeDecision.Decision;
import com.argus.security.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent 5 recommendation endpoints for the Probability Forecast Card UI (Story 6.3), session-gated
 * under {@code /api/recommendations}. Returns the auditable probabilities/confidence, the per-agent
 * diagnostic, the graduation badge, and the Black-Swan confidence cap; and accepts the user's
 * Taken/Declined decision (Story 6.7).
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

	/** When a Black Swan is active, confidence is capped (FR-13). No Black-Swan source exists yet (Epic 10). */
	private static final BigDecimal BLACK_SWAN_CONFIDENCE_CAP = new BigDecimal("0.60");

	private final RecommendationService recommendations;
	private final TradeConfirmationService confirmation;
	private final GraduationService graduation;
	private final RecommendationDebateService debates;
	private final com.argus.technical.ChartStudyService charts;
	private final com.argus.deepanalysis.DeepAnalysisService deepAnalyses;
	private final com.argus.technical.LivePriceService prices;
	private final CurrentUserService currentUser;

	public RecommendationController(RecommendationService recommendations,
			TradeConfirmationService confirmation, GraduationService graduation,
			RecommendationDebateService debates, com.argus.technical.ChartStudyService charts,
			com.argus.deepanalysis.DeepAnalysisService deepAnalyses, com.argus.technical.LivePriceService prices,
			CurrentUserService currentUser) {
		this.recommendations = recommendations;
		this.confirmation = confirmation;
		this.graduation = graduation;
		this.debates = debates;
		this.charts = charts;
		this.deepAnalyses = deepAnalyses;
		this.prices = prices;
		this.currentUser = currentUser;
	}

	@GetMapping
	public List<RecommendationCard> list() {
		boolean blackSwan = isBlackSwanActive();
		GraduationState state = graduation.currentState();
		List<Recommendation> recent = recommendations.recent();
		java.util.Map<Long, Instant> since = recommendations.callSince(recent.stream().map(Recommendation::getId).toList());
		return recent.stream().map(r -> card(r, state, blackSwan, since.get(r.getId()))).toList();
	}

	/** Tickers Argus is watching but has no clear edge on — with the reason — so silence is explained. */
	@GetMapping("/watching")
	public List<WatchItem> watching() {
		return recommendations.watching().stream().map(WatchItem::from).toList();
	}

	/** The card plus what Agent 10 (the chart) and Agent 11 (the deep analysis) contributed to it. */
	private RecommendationCard card(Recommendation r, GraduationState state, boolean blackSwan, Instant callSince) {
		// Streaming price for holdings, else a polled quote — watchlist names have no stream.
		Double lastPrice = prices.livePrice(r.getTicker()).orElse(null);
		// Levels re-measured from the live price, so a level broken today isn't shown on the wrong side of it.
		com.argus.technical.ChartStudy chart = charts.studyFor(r.getTicker(), lastPrice).orElse(null);
		com.argus.deepanalysis.DeepView deep = deepAnalyses.viewFor(r.getTicker()).orElse(null);
		String valuation = token(r, "val=");
		PriceGuidance.Guidance guidance = PriceGuidance.build(r.getAction(), r.getHoldDays() == null ? 0 : r.getHoldDays(),
				lastPrice, chart, deep, valuation);
		return RecommendationCard.from(r, state, blackSwan, chart == null ? null : ChartView.from(chart, lastPrice),
				deep == null ? null : DeepSummary.from(deep), GuidanceView.from(guidance), callSince);
	}

	@GetMapping("/graduation")
	public GraduationService.GraduationSummary graduation() {
		return graduation.summary();
	}

	/** Manual review (Story 6.6) — resume a FROZEN Agent 5 back to SHADOW. Admin-only (S-A3). */
	@PostMapping("/graduation/resume")
	public GraduationService.GraduationSummary resumeGraduation(HttpServletRequest request) {
		currentUser.requireAdmin(request);
		graduation.resume();
		return graduation.summary();
	}

	@GetMapping("/{id}")
	public RecommendationCard get(@PathVariable Long id) {
		return recommendations.diagnostic(id)
				.map(r -> card(r, graduation.currentState(), isBlackSwanActive(),
						recommendations.callSince(List.of(r.getId())).get(r.getId())))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	@PostMapping("/{id}/decision")
	public void decide(@PathVariable Long id, @RequestBody DecisionBody body) {
		confirmation.confirm(id, body.decision(), body.reasoning(), body.entryPrice(), body.positionSize());
	}

	/** Runs a fresh bull-vs-bear researcher debate ("Debate this call", user-triggered only —
	 * escalates to Claude Haiku). 404 on a bad id, 503 when the model call/parse fails, so the two
	 * failure modes don't collapse into one status. */
	@PostMapping("/{id}/debate")
	public DebateView runDebate(@PathVariable Long id) {
		recommendations.diagnostic(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return debates.debate(id).map(DebateView::from)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@GetMapping("/{id}/debate")
	public List<DebateView> debateHistory(@PathVariable Long id) {
		return debates.historyFor(id).stream().map(DebateView::from).toList();
	}

	/** Black Swan state is not modeled yet (Epic 10) — always false; the cap is wired for when it is. */
	private boolean isBlackSwanActive() {
		return false;
	}

	/** {@code entryPrice}/{@code positionSize} are optional (Story 11.1, F22) — meaningful only for a
	 * TAKEN decision; the client omits or sends null for either when not reported. */
	public record DecisionBody(Decision decision, String reasoning, BigDecimal entryPrice, BigDecimal positionSize) {
	}

	/**
	 * The recommendation card: the call (action + 0-100 conviction score), how long to hold it, the
	 * reasoning and risks behind it, and — kept for the diagnostic view — the raw probabilities and
	 * per-agent signals. {@code action}/{@code convictionScore}/{@code holdDays} are null only on legacy rows.
	 * {@code callSince} is when this call (same action, unbroken) was first made; {@code createdAt} is
	 * when it was last re-checked.
	 */
	public record RecommendationCard(Long id, String ticker, String direction, BigDecimal bullProbability,
			BigDecimal bearProbability, BigDecimal confidence, boolean confidenceCapped, BigDecimal priceTarget,
			String horizon, String status, String badge, boolean blackSwanActive, Instant createdAt,
			List<SignalView> signals, String action, String actionLabel, Integer convictionScore, Integer holdDays,
			String horizonLabel, LocalDate reviewOn, String thesis, List<String> reasons, List<String> caveats,
			String exitPlan, String sector, List<String> learned, ChartView chart, DeepSummary deep, String guidance, String valuation,
			GuidanceView priceGuidance, Instant callSince) {

		static RecommendationCard from(Recommendation r, GraduationState state, boolean blackSwan, ChartView chart, DeepSummary deep, GuidanceView priceGuidance,
				Instant callSince) {
			BigDecimal confidence = r.getConfidence();
			boolean capped = blackSwan && confidence.compareTo(BLACK_SWAN_CONFIDENCE_CAP) > 0;
			if (capped) {
				confidence = BLACK_SWAN_CONFIDENCE_CAP;
			}
			RecommendationAction action = r.getAction();
			LocalDate reviewOn = r.getHoldDays() == null ? null
					: r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().plusDays(r.getHoldDays());
			return new RecommendationCard(r.getId(), r.getTicker(), r.getDirection().name(),
					r.getBullProbability(), r.getBearProbability(), confidence, capped, r.getPriceTarget(),
					r.getHorizon(), r.getStatus().name(), state.badge(), blackSwan, r.getCreatedAt(),
					r.getSignals().stream().map(SignalView::from).toList(),
					action == null ? null : action.name(), action == null ? null : action.label(),
					r.getConvictionScore(), r.getHoldDays(), r.getHorizonLabel(), reviewOn, r.getThesis(),
					lines(r.getReasons()), lines(r.getCaveats()), r.getExitPlan(), sectorLabel(r.getSector()),
					lines(r.getLessons()), chart, deep, token(r, "guidance="), token(r, "val="), priceGuidance,
					callSince == null ? r.getCreatedAt() : callSince);
		}
	}

	/** The value of a feature token the call was made under (e.g. {@code guidance=RAISED} → RAISED), or null. */
	private static String token(Recommendation r, String prefix) {
		if (r.getFeatures() == null) return null;
		return com.argus.learning.FeatureTokens.fromJson(r.getFeatures()).stream().filter(t -> t.startsWith(prefix))
				.map(t -> t.substring(prefix.length())).findFirst().orElse(null);
	}

	/** Agent 10's chart read on the card: bias, score, trend, and the evidence lines behind it. */
	/**
	 * Agent 10's read for the card. {@code levelsPrice} is the price support/resistance were measured
	 * from — the live price when {@code levelsLive}, otherwise the last daily close — and {@code barsThrough}
	 * is the newest daily candle behind the study, so the UI can say exactly how current it is.
	 */
	public record ChartView(String bias, double score, String trend, List<String> notes, Double support, Double resistance,
			double levelsPrice, boolean levelsLive, LocalDate barsThrough) {

		static ChartView from(com.argus.technical.ChartStudy s, Double livePrice) {
			boolean live = com.argus.technical.ChartStudyService.livePriceUsable(s, livePrice);
			return new ChartView(s.bias(), s.score(), s.trend().name(), s.notes().stream().limit(5).toList(), s.support(),
					s.resistance(), live ? livePrice : s.lastClose(), live, s.asOf());
		}
	}

	/**
	 * What price to act at for a human following this call ({@link PriceGuidance}): buy/sell/stop levels and why,
	 * or null (WATCH, or a stopped/finished call). A "core holding" call (style CORE_HOLD) has no {@code sellPrice}
	 * — {@code sellNote} explains that it's held/accumulated as long as the thesis stands.
	 */
	public record GuidanceView(BigDecimal buyPrice, String buyNote, BigDecimal sellPrice, String sellNote,
			BigDecimal stopPrice, String stopNote, String style) {

		static GuidanceView from(PriceGuidance.Guidance g) {
			return g == null ? null : new GuidanceView(g.buyPrice(), g.buyNote(), g.sellPrice(), g.sellNote(),
					g.stopPrice(), g.stopNote(), g.style().name());
		}
	}

	/** Agent 11's latest fresh verdict on the card. */
	public record DeepSummary(String verdict, String verdictLabel, Integer holdDays, int conviction, String headline, long ageDays,
			boolean atRisk, String atRiskReason) {

		static DeepSummary from(com.argus.deepanalysis.DeepView v) {
			return new DeepSummary(v.verdict().name(), v.verdict().label(), v.holdDays(), v.conviction(), v.headline(), v.ageDays(), v.atRisk(),
					v.atRiskReason());
		}
	}

	/** A name Argus is watching but making no call on, and why. */
	public record WatchItem(Long id, String ticker, Integer convictionScore, String reason, String sector,
			Instant createdAt) {

		static WatchItem from(Recommendation r) {
			return new WatchItem(r.getId(), r.getTicker(), r.getConvictionScore(), r.getThesis(),
					sectorLabel(r.getSector()), r.getCreatedAt());
		}
	}

	private static List<String> lines(String joined) {
		return joined == null || joined.isBlank() ? List.of() : java.util.Arrays.asList(joined.split("\n"));
	}

	private static String sectorLabel(String name) {
		try {
			return name == null ? null : com.argus.regime.Sector.valueOf(name).label();
		}
		catch (IllegalArgumentException ex) {
			return name;
		}
	}

	/** One agent's diagnostic row for the card's signal dots + expandable breakdown. */
	public record SignalView(String agent, String direction, BigDecimal weight, String rationale) {

		static SignalView from(RecommendationSignal s) {
			return new SignalView(s.getAgent(), s.getDirection().name(), s.getWeight(), s.getRationale());
		}
	}

	/** One bull-vs-bear researcher debate run. */
	public record DebateView(Long id, String bullCase, String bearCase, String synthesis, String verdict,
			Instant createdAt) {

		static DebateView from(RecommendationDebate d) {
			return new DebateView(d.getId(), d.getBullCase(), d.getBearCase(), d.getSynthesis(),
					d.getVerdict().name(), d.getCreatedAt());
		}
	}
}
