package com.argus.portfolio;

import com.argus.security.AppUser;
import com.argus.security.AppUserRepository;
import com.argus.security.CurrentUserContext;
import com.argus.technical.PriceCandle;
import com.argus.technical.PriceCandleRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Portfolio Health Score (Story 3.8, FR-6) — a deterministic, auditable rule/weight engine.
 * <b>Never an LLM</b> (Argus framing rule: scores are model-derived, not language-model-generated).
 * Starts at 100 and deducts for concentration (single-name + top-3 + correlated-cluster), thin
 * diversification, and unconfirmed data; agent-sentiment / risk-alert inputs are explicit no-ops
 * until those epics exist. Weighting uses CAD ACB (always available) so the score is deterministic
 * without live prices.
 */
@Service
public class HealthScoreService {

	private static final Logger log = LoggerFactory.getLogger(HealthScoreService.class);
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	/** A pair of holdings must clear both this correlation strength... */
	private static final double CORRELATION_THRESHOLD = 0.7;
	/** ...and this combined portfolio weight before it's flagged — statistical significance alone
	 * isn't the point; it only matters if it represents real dollar exposure. */
	private static final double MIN_COMBINED_WEIGHT = 0.15;
	/** Below this many shared trading days, a correlation read is too thin to trust — silence over
	 * a wrong guess, same discipline as every other data-driven read in this app. */
	private static final int MIN_COMMON_TRADING_DAYS = 20;

	private final PositionRepository positions;
	private final HealthScoreRepository scores;
	private final PriceCandleRepository candles;
	private final AppUserRepository users;
	private final ObjectMapper json = JsonMapper.builder().build();

	public HealthScoreService(PositionRepository positions, HealthScoreRepository scores,
			PriceCandleRepository candles, AppUserRepository users) {
		this.positions = positions;
		this.scores = scores;
		this.candles = candles;
		this.users = users;
	}

	/** Compute the current score + explained deductions. Empty portfolio → 100 (nothing at risk). */
	@Transactional(readOnly = true)
	public HealthScoreResult compute() {
		List<Position> ps = positions.findAllByOrderByTickerAsc();
		List<HealthDeduction> deductions = new ArrayList<>();
		if (ps.isEmpty()) {
			return new HealthScoreResult(100, List.of(), Instant.now());
		}

		concentration(ps, deductions);
		correlationRisk(ps, deductions);
		diversification(ps, deductions);
		dataQuality(ps, deductions);
		// agentSentiment / openRiskAlerts / pendingActions → stubbed (Epics 4/6/8).

		int sum = deductions.stream().mapToInt(HealthDeduction::points).sum();
		int score = Math.max(0, Math.min(100, 100 - sum));
		return new HealthScoreResult(score, List.copyOf(deductions), Instant.now());
	}

	/** Each position's share of total CAD cost basis, summed per ticker (a ticker held across
	 * multiple accounts/institutions is one real economic exposure, not several). Empty when no
	 * position has a priced cost basis (e.g. all FX-estimated). */
	private Map<String, Double> cadWeightsByTicker(List<Position> ps) {
		double total = ps.stream().map(Position::getCadAcb).filter(Objects::nonNull)
				.mapToDouble(BigDecimal::doubleValue).filter(v -> v > 0).sum();
		if (total <= 0) {
			return Map.of();
		}
		return ps.stream()
				.filter(p -> p.getCadAcb() != null && p.getCadAcb().signum() > 0)
				.collect(Collectors.toMap(Position::getTicker, p -> p.getCadAcb().doubleValue() / total,
						Double::sum));
	}

	private void concentration(List<Position> ps, List<HealthDeduction> out) {
		Map<String, Double> weights = cadWeightsByTicker(ps);
		if (weights.isEmpty()) {
			return; // no priced cost basis → can't weight (e.g. all FX-estimated)
		}
		List<Map.Entry<String, Double>> sorted = weights.entrySet().stream()
				.sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();

		Map.Entry<String, Double> top = sorted.get(0);
		if (top.getValue() > 0.25) {
			int pts = Math.min(20, (int) Math.round((top.getValue() - 0.25) * 100));
			if (pts > 0) {
				out.add(new HealthDeduction("concentration_single", "Single-name concentration", pts,
						top.getKey() + " is " + pct(top.getValue()) + " of your portfolio",
						"Trim " + top.getKey() + " or add other holdings"));
			}
		}
		double top3 = sorted.stream().limit(3).mapToDouble(Map.Entry::getValue).sum();
		if (top3 > 0.60) {
			int pts = Math.min(20, (int) Math.round((top3 - 0.60) * 100));
			if (pts > 0) {
				out.add(new HealthDeduction("concentration_top3", "Top-heavy portfolio", pts,
						"Top 3 holdings are " + pct(top3) + " of your portfolio",
						"Diversify beyond your largest names"));
			}
		}
	}

	/**
	 * Correlated-cluster concentration: the same underlying risk {@link #concentration} checks by
	 * single-name weight, but for holdings that <em>move together</em> — five "different" tickers
	 * that are secretly one correlated bet are exactly the hidden risk a single-name check can't
	 * see. Flags at most the single worst pair (matches every other category emitting one finding),
	 * requiring both real statistical correlation ({@link #CORRELATION_THRESHOLD}) and real combined
	 * dollar exposure ({@link #MIN_COMBINED_WEIGHT}) — correlation alone, or size alone, isn't the
	 * point. Silent whenever there isn't enough shared daily-candle history yet (Agent 10's own
	 * ingestion, not this service's concern) to trust a read.
	 */
	private void correlationRisk(List<Position> ps, List<HealthDeduction> out) {
		Map<String, Double> weights = cadWeightsByTicker(ps);
		if (weights.size() < 2) {
			return;
		}
		Map<String, List<PriceCandle>> ascendingByTicker = new LinkedHashMap<>();
		for (String ticker : weights.keySet()) {
			List<PriceCandle> recent = candles.findTop200ByTickerOrderByCandleDateDesc(ticker);
			if (recent.size() < MIN_COMMON_TRADING_DAYS) {
				continue;
			}
			List<PriceCandle> ascending = new ArrayList<>(recent);
			Collections.reverse(ascending);
			ascendingByTicker.put(ticker, ascending);
		}
		if (ascendingByTicker.size() < 2) {
			return;
		}

		Set<LocalDate> commonDates = null;
		for (List<PriceCandle> series : ascendingByTicker.values()) {
			Set<LocalDate> dates = series.stream().map(PriceCandle::getCandleDate).collect(Collectors.toSet());
			commonDates = commonDates == null ? dates : retainCommon(commonDates, dates);
		}
		if (commonDates == null || commonDates.size() < MIN_COMMON_TRADING_DAYS) {
			return;
		}

		List<LocalDate> sortedDates = commonDates.stream().sorted().toList();
		List<String> tickers = List.copyOf(ascendingByTicker.keySet());
		double[][] returns = new double[sortedDates.size() - 1][tickers.size()];
		for (int col = 0; col < tickers.size(); col++) {
			Map<LocalDate, BigDecimal> closeByDate = ascendingByTicker.get(tickers.get(col)).stream()
					.collect(Collectors.toMap(PriceCandle::getCandleDate, PriceCandle::getClose));
			for (int row = 1; row < sortedDates.size(); row++) {
				BigDecimal prev = closeByDate.get(sortedDates.get(row - 1));
				BigDecimal curr = closeByDate.get(sortedDates.get(row));
				returns[row - 1][col] = curr.subtract(prev).divide(prev, MathContext.DECIMAL64).doubleValue();
			}
		}

		RealMatrix corr = new PearsonsCorrelation().computeCorrelationMatrix(returns);
		String worstA = null;
		String worstB = null;
		double worstCorr = 0;
		double worstCombinedWeight = 0;
		double worstScore = 0;
		for (int i = 0; i < tickers.size(); i++) {
			for (int j = i + 1; j < tickers.size(); j++) {
				double c = corr.getEntry(i, j);
				// Only POSITIVE correlation is risk — two holdings that move up and down together
				// amplify the same bet. Strong negative correlation is the opposite: a natural
				// hedge, not concentration risk, so it must never be flagged here.
				if (Double.isNaN(c) || c < CORRELATION_THRESHOLD) {
					continue;
				}
				double combinedWeight = weights.get(tickers.get(i)) + weights.get(tickers.get(j));
				if (combinedWeight < MIN_COMBINED_WEIGHT) {
					continue;
				}
				double score = c * combinedWeight;
				if (score > worstScore) {
					worstScore = score;
					worstA = tickers.get(i);
					worstB = tickers.get(j);
					worstCorr = c;
					worstCombinedWeight = combinedWeight;
				}
			}
		}
		if (worstA == null) {
			return;
		}
		int pts = Math.min(15, (int) Math.round((worstScore - CORRELATION_THRESHOLD * MIN_COMBINED_WEIGHT) * 60));
		if (pts <= 0) {
			return;
		}
		out.add(new HealthDeduction("correlation_risk", "Correlated holdings", pts,
				worstA + " and " + worstB + " are " + pct(worstCorr) + " correlated and together are "
						+ pct(worstCombinedWeight) + " of your portfolio",
				"A single shock could hit both at once — consider trimming one or diversifying into an "
						+ "uncorrelated sector"));
	}

	private static Set<LocalDate> retainCommon(Set<LocalDate> a, Set<LocalDate> b) {
		Set<LocalDate> out = new HashSet<>(a);
		out.retainAll(b);
		return out;
	}

	private void diversification(List<Position> ps, List<HealthDeduction> out) {
		int n = ps.size();
		if (n < 5) {
			out.add(new HealthDeduction("diversification", "Few holdings", (5 - n) * 4,
					"Only " + n + " holding" + (n == 1 ? "" : "s"),
					"Add more positions to spread risk"));
		}
	}

	private void dataQuality(List<Position> ps, List<HealthDeduction> out) {
		long flagged = ps.stream().filter(p -> p.isNeedsReview() || p.isFxEstimated()).count();
		if (flagged > 0) {
			out.add(new HealthDeduction("data_quality", "Unconfirmed data", (int) Math.min(10, flagged * 2),
					flagged + " position" + (flagged == 1 ? "" : "s") + " need review or FX confirmation",
					"Confirm purchase FX / review flagged holdings"));
		}
	}

	/** Upsert today's score point for the trend (idempotent). */
	@Transactional
	public void capture() {
		// Don't record a daily point for an empty portfolio — a flat 100 baseline before any
		// holdings exist isn't a meaningful trend.
		if (positions.count() == 0) {
			return;
		}
		HealthScoreResult result = compute();
		String breakdown = json.writeValueAsString(result.deductions());
		LocalDate today = LocalDate.now(TORONTO);
		scores.findByScoredOn(today)
				.ifPresentOrElse(s -> {
					s.update(result.score(), breakdown);
					scores.save(s);
				}, () -> scores.save(new HealthScore(today, result.score(), breakdown)));
	}

	/** The daily score series for the last {@code days} (clamped 1–365), ascending (Story 3.9). */
	@Transactional(readOnly = true)
	public List<HealthPoint> history(int days) {
		int window = Math.max(1, Math.min(365, days));
		LocalDate from = LocalDate.now(TORONTO).minusDays(window);
		return scores.findByScoredOnGreaterThanEqualOrderByScoredOnAsc(from).stream()
				.map(s -> new HealthPoint(s.getScoredOn(), s.getScore()))
				.toList();
	}

	/** Daily recompute (06:00 ET — after overnight agent runs land in later epics), for every person in
	 * turn. One person's capture failing never stops the others'. */
	@Scheduled(cron = "0 0 6 * * *", zone = "America/New_York")
	public void scheduledCapture() {
		for (AppUser user : users.findAll()) {
			try {
				CurrentUserContext.runAs(user.getId(), this::capture);
			} catch (RuntimeException ex) {
				log.warn("Scheduled health-score capture failed for user {}: {}", user.getId(), ex.getMessage());
			}
		}
	}

	private static String pct(double weight) {
		return Math.round(weight * 100) + "%";
	}
}
