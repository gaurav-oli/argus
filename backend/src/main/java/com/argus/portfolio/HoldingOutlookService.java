package com.argus.portfolio;

import com.argus.deepanalysis.DeepAnalysisService;
import com.argus.deepanalysis.DeepView;
import com.argus.recommendation.HoldingOutlook;
import com.argus.recommendation.PriceGuidance;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationRepository;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * For each of the signed-in person's own holdings: does Argus currently think this is a genuine
 * long-term compounder worth continuing to accumulate, or should it be reconsidered ({@link
 * HoldingOutlook})? Plus two smaller, related nudges: a caution when the holding sits in a registered
 * account (TFSA/RRSP/RESP/RRIF/LIRA — frequent trading there carries real CRA "business income" risk,
 * separate from whether the stock itself is good), and a reminder to log a change when a long-held
 * "keep" position hasn't been touched in a while.
 *
 * <p>Nothing here is persisted or newly computed by a model — it reads the SAME recommendation/
 * deep-analysis data already shown elsewhere, just reframed against what this person actually owns.
 */
@Service
public class HoldingOutlookService {

	/** A latest recommendation older than this is too stale to confidently classify a holding — fall
	 * back to "not enough data" rather than lean on a read-out that predates recent news/price action.
	 * Held tickers are re-scored every 6h, so a gap this long means scoring stopped (an earnings quiet
	 * period is ~2 trading days); it used to be 30 days, long enough to present a month-old call as current. */
	static final Duration STALE_AFTER = Duration.ofDays(7);
	/** A "keep" position not edited in this long gets a nudge to confirm it's still being added to. */
	private static final Duration NUDGE_AFTER = Duration.ofDays(21);

	private static final java.util.Set<String> REGISTERED_ACCOUNT_TYPES =
			java.util.Set.of("TFSA", "RRSP", "RRIF", "RESP", "LIRA");

	private final PositionRepository positions;
	private final AccountMetaRepository accountMeta;
	private final RecommendationRepository recommendations;
	private final DeepAnalysisService deepAnalyses;
	private final ChartStudyService charts;
	private final LivePortfolioService livePrices;

	public HoldingOutlookService(PositionRepository positions, AccountMetaRepository accountMeta,
			RecommendationRepository recommendations, DeepAnalysisService deepAnalyses, ChartStudyService charts,
			LivePortfolioService livePrices) {
		this.positions = positions;
		this.accountMeta = accountMeta;
		this.recommendations = recommendations;
		this.deepAnalyses = deepAnalyses;
		this.charts = charts;
		this.livePrices = livePrices;
	}

	@Transactional(readOnly = true)
	public List<HoldingOutlookView> outlooks() {
		Map<String, AccountMeta> ownerByKey = new HashMap<>();
		for (AccountMeta m : accountMeta.findAll()) {
			ownerByKey.put(LivePortfolioService.accountKey(m.getInstitution(), m.getAccount()), m);
		}
		// One Recommendation/DeepAnalysis/ChartStudy read per distinct ticker, even if held across
		// several accounts — the outlook is a read on the STOCK, not on any one account row.
		Map<String, TickerRead> readByTicker = new HashMap<>();

		return positions.findAllByOrderByTickerAsc().stream().map(p -> {
			TickerRead read = readByTicker.computeIfAbsent(p.getTicker(), this::readTicker);

			AccountMeta meta = ownerByKey.get(LivePortfolioService.accountKey(p.getInstitution(), p.getAccount()));
			String accountType = meta != null ? meta.getAccountType() : null;
			boolean registered = isRegistered(accountType);

			return new HoldingOutlookView(p.getId(), p.getTicker(), read.outlook.verdict().name(),
					read.outlook.verdict().label(), read.outlook.reason(), read.timingNote, registered,
					registered ? "This is a registered account (" + accountType + ") — frequent trading here can "
							+ "trigger CRA \"business income\" reclassification. Best treated as buy-and-hold." : null,
					nudgeFor(p, read.outlook.verdict()));
		}).toList();
	}

	/** Everything derived from the ticker's single latest (non-stale) recommendation, read once. */
	private record TickerRead(HoldingOutlook.Outlook outlook, String timingNote) {
	}

	private TickerRead readTicker(String ticker) {
		Optional<Recommendation> rec = recommendations.findFirstByTickerOrderByCreatedAtDescIdDesc(ticker)
				.filter(r -> Duration.between(r.getCreatedAt(), Instant.now()).compareTo(STALE_AFTER) <= 0);
		if (rec.isEmpty()) {
			return new TickerRead(HoldingOutlook.classify(null, null, null), null);
		}
		Recommendation r = rec.get();
		DeepView deep = deepAnalyses.viewFor(ticker).orElse(null);
		Double lastPrice = livePrices.latestPrice(ticker).map(BigDecimal::doubleValue).orElse(null);
		// Levels re-measured from the live price, so "good time to add?" doesn't lean on yesterday's levels.
		ChartStudy chart = charts.studyFor(ticker, lastPrice).orElse(null);
		String valuation = valuationToken(r);
		PriceGuidance.Guidance guidance = PriceGuidance.build(r.getAction(), r.getHoldDays() == null ? 0 : r.getHoldDays(),
				lastPrice, chart, deep, valuation);
		HoldingOutlook.Outlook outlook = HoldingOutlook.classify(r, deep, guidance);
		// The existing chart-derived buy note ("at or near the current price" / "a pullback is a better
		// entry") reused verbatim as the qualitative "good time to add, or wait" steer — never a new
		// invented number.
		String timingNote = guidance == null ? null : guidance.buyNote();
		return new TickerRead(outlook, timingNote);
	}

	private static boolean isRegistered(String accountType) {
		return accountType != null && REGISTERED_ACCOUNT_TYPES.contains(accountType.toUpperCase());
	}

	private static String nudgeFor(Position p, HoldingOutlook.Verdict verdict) {
		if (verdict != HoldingOutlook.Verdict.KEEP || p.getUpdatedAt() == null) {
			return null;
		}
		long days = Duration.between(p.getUpdatedAt(), Instant.now()).toDays();
		if (days < NUDGE_AFTER.toDays()) {
			return null;
		}
		return "You marked " + p.getTicker() + " as a long-term pick " + humanDays(days) + " ago — bought more since?";
	}

	private static String humanDays(long days) {
		if (days < 14) {
			return days + " day" + (days == 1 ? "" : "s");
		}
		long weeks = days / 7;
		if (weeks < 8) {
			return weeks + " week" + (weeks == 1 ? "" : "s");
		}
		long months = days / 30;
		return months + " month" + (months == 1 ? "" : "s");
	}

	/** The valuation feature token ({@code val=CHEAP|FAIR|RICH}) a recommendation was made under, if any —
	 * same extraction {@code RecommendationController} already does for the price-guidance card. */
	private static String valuationToken(Recommendation r) {
		if (r.getFeatures() == null) {
			return null;
		}
		return com.argus.learning.FeatureTokens.fromJson(r.getFeatures()).stream()
				.filter(t -> t.startsWith("val=")).map(t -> t.substring(4)).findFirst().orElse(null);
	}

	/** One holding's long-term outlook. {@code outlook} is {@code KEEP}/{@code RECONSIDER}/
	 * {@code NOT_ENOUGH_DATA}; {@code registeredAccountNote}/{@code updateNudge} are null when not applicable. */
	public record HoldingOutlookView(Long positionId, String ticker, String outlook, String outlookLabel,
			String reason, String timingNote, boolean registeredAccount, String registeredAccountNote,
			String updateNudge) {
	}
}
