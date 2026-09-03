package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import com.argus.sec.SecFiling;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import com.argus.technical.CauseClassificationService;
import com.argus.technical.PriceCandle;
import com.argus.technical.PriceCandleRepository;
import com.argus.technical.TechnicalAnalysisProperties;
import com.argus.technical.TechnicalIndicators;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link AgentSignal}s the scoring engine needs for a ticker (Story 6.4) from the
 * agents that currently exist: Agent 1 (news sentiment, weighted by coverage and relevance),
 * Agent 2 (social crowd sentiment, weighted by volume and conviction — capped lower since the crowd
 * is noisier), Agent 7 (the earnings calendar), and Agent 8 (macro/political news — tariffs, Fed
 * policy — that moves every held ticker, not just whichever one happens to be named in the
 * headline). As more agents come online they add their signals here; until then coverage is
 * honestly low (lowering confidence).
 */
@Component
public class AgentSignalGatherer {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentSignalGatherer.class);

	/**
	 * Net bullish/bearish skew must exceed this deadzone before a source calls a direction; inside it the
	 * signal reads NEUTRAL. Shared by the social and internet signals so their thresholds can't drift.
	 */
	private static final double SIGNAL_DIRECTION_DEADZONE = 0.15;

	private static final Duration NEWS_WINDOW = Duration.ofDays(7);
	private static final int FULL_COVERAGE_ARTICLES = 5;
	/** Macro/political news cycles and market reactions move faster than company-specific coverage. */
	private static final Duration MACRO_WINDOW = Duration.ofDays(3);
	private static final int MACRO_FULL_COVERAGE = 3;
	/** Unproven signal (freshly admitted) — capped below news until its own track record earns more. */
	private static final double MACRO_MAX_WEIGHT = 0.5;
	private static final Duration SOCIAL_WINDOW = Duration.ofDays(3);
	private static final int SOCIAL_MIN_POSTS = 5;
	private static final int SOCIAL_FULL_VOLUME = 30;
	/** Crowd sentiment is noisier than curated news — cap its max influence below Agent 1's. */
	private static final double SOCIAL_MAX_WEIGHT = 0.6;

	private static final int INSIDER_WINDOW_DAYS = 30;

	private static final Duration INTERNET_WINDOW = Duration.ofDays(14);
	private static final Duration INTERNET_RECENT = Duration.ofDays(3);
	private static final double ATTENTION_SPIKE = 1.3;
	/** Internet buzz is the noisiest, lowest-ROI source — hard-cap its influence below all others. */
	private static final double INTERNET_MAX_WEIGHT = 0.35;

	/** Deterministic RSI/trend read — a supporting/corroborating signal, same order of magnitude
	 * as News, not the override mechanism (that's {@link #CAUSE_MAX_WEIGHT} below). */
	private static final double TECHNICAL_MAX_WEIGHT = 0.7;
	/** Deliberately higher than every other agent's cap — the concrete mechanism for "a highly
	 * confident macro-external-and-temporary classification can outweigh a bearish News signal in
	 * the same weighted average" (no veto/branching logic added to the scoring engine itself; see
	 * {@link com.argus.recommendation.ProbabilityScoringEngine}). Still bounded, still has to earn
	 * real trust through {@link AdaptiveTuningService}/Logic Review like every other agent. */
	private static final double CAUSE_MAX_WEIGHT = 1.5;

	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final SecFilingRepository sec;
	private final WebMentionRepository web;
	private final EarningsQuietPeriodService quietPeriod;
	private final AdaptiveTuningService tuning;
	private final PriceCandleRepository candles;
	private final CauseClassificationService causeClassification;
	private final TechnicalAnalysisProperties technicalProps;
	private final NotificationService notifications;

	public AgentSignalGatherer(NewsArticleRepository news, SocialPostRepository social,
			SecFilingRepository sec, WebMentionRepository web, EarningsQuietPeriodService quietPeriod,
			AdaptiveTuningService tuning, PriceCandleRepository candles,
			CauseClassificationService causeClassification, TechnicalAnalysisProperties technicalProps,
			NotificationService notifications) {
		this.news = news;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.quietPeriod = quietPeriod;
		this.tuning = tuning;
		this.candles = candles;
		this.causeClassification = causeClassification;
		this.technicalProps = technicalProps;
		this.notifications = notifications;
	}

	public List<AgentSignal> gather(String ticker) {
		List<AgentSignal> signals = new ArrayList<>();
		newsSignal(ticker).ifPresent(signals::add);
		macroSignal().ifPresent(signals::add);
		socialSignal(ticker).ifPresent(signals::add);
		insiderSignal(ticker).ifPresent(signals::add);
		internetSignal(ticker).ifPresent(signals::add);
		calendarSignal(ticker).ifPresent(signals::add);
		List<PriceCandle> ascendingCandles = ascendingCandles(ticker);
		technicalSignal(ascendingCandles).ifPresent(signals::add);
		causeSignal(ticker, ascendingCandles).ifPresent(signals::add);
		// Phase B: scale each agent's weight by its learned reliability (identity when tuning is disabled).
		List<AgentSignal> tuned = signals.stream().map(this::applyReliability).toList();
		log.info("gather({}) → [{}]", ticker,
				tuned.stream().map(AgentSignal::agent).reduce((a, b) -> a + "," + b).orElse("NONE"));
		return tuned;
	}

	/** Multiply a signal's weight by its agent's learned reliability multiplier. */
	private AgentSignal applyReliability(AgentSignal s) {
		double mult = tuning.weightMultiplier(s.agent());
		if (mult == 1.0) {
			return s;
		}
		return new AgentSignal(s.agent(), s.direction(), s.weight() * mult, s.rationale());
	}

	/**
	 * Agent 3 — internet attention. Direction from Hacker News story sentiment (when present); a
	 * Wikipedia pageview spike over baseline adds conviction. Capped well below the other agents —
	 * it's broad-web noise, useful mainly as corroboration.
	 */
	private Optional<AgentSignal> internetSignal(String ticker) {
		List<WebMention> recent = web.findByTickerAndPostedAtAfter(ticker, Instant.now().minus(INTERNET_WINDOW));
		if (recent.isEmpty()) {
			return Optional.empty();
		}
		long hn = recent.stream().filter(m -> "hackernews".equals(m.getSource())).count();
		long bull = recent.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BULLISH).count();
		long bear = recent.stream().filter(m -> m.getSentimentLabel() == SentimentLabel.BEARISH).count();

		Instant recentCut = Instant.now().minus(INTERNET_RECENT);
		List<WebMention> wiki = recent.stream().filter(m -> "wikipedia".equals(m.getSource())).toList();
		double recentAvg = wiki.stream().filter(m -> m.getPostedAt().isAfter(recentCut))
				.mapToLong(WebMention::getScore).average().orElse(0);
		double baseAvg = wiki.stream().filter(m -> !m.getPostedAt().isAfter(recentCut))
				.mapToLong(WebMention::getScore).average().orElse(0);
		double ratio = baseAvg > 0 ? recentAvg / baseAvg : 0;
		boolean spike = ratio >= ATTENTION_SPIKE;

		long scored = bull + bear;
		if (scored == 0 && !spike) {
			return Optional.empty(); // attention is steady and undirected — nothing to add
		}
		double net = scored == 0 ? 0 : (double) (bull - bear) / scored;
		SignalDirection dir = net > SIGNAL_DIRECTION_DEADZONE ? SignalDirection.BULLISH
				: net < -SIGNAL_DIRECTION_DEADZONE ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double conviction = Math.min(1.0, hn / 10.0) * Math.abs(net);
		double spikeBoost = spike ? Math.min(1.0, ratio - 1.0) : 0;
		double weight = Math.min(INTERNET_MAX_WEIGHT, 0.1 + 0.2 * conviction + 0.1 * spikeBoost);
		String rationale = String.format("Web buzz: %d HN stories (%d↑/%d↓)%s", hn, bull, bear,
				spike ? String.format(", Wikipedia attention %.1f× baseline", ratio) : "");
		return Optional.of(new AgentSignal("agent-3-internet", dir, weight, rationale));
	}

	/**
	 * Agent 4 — insider (Form 4) activity. Open-market PURCHASES are a strong bullish signal (rare,
	 * high conviction); SALES are common and routine, so they read mildly bearish at low weight.
	 */
	private Optional<AgentSignal> insiderSignal(String ticker) {
		List<SecFiling> filings = sec.findByTickerAndTransactionTypeInAndFiledAtAfter(ticker,
				List.of("BUY", "SELL"), LocalDate.now().minusDays(INSIDER_WINDOW_DAYS));
		long buys = filings.stream().filter(f -> "BUY".equals(f.getTransactionType())).count();
		long sells = filings.stream().filter(f -> "SELL".equals(f.getTransactionType())).count();
		if (buys == 0 && sells == 0) {
			return Optional.empty();
		}
		if (buys > 0) {
			double weight = Math.min(0.8, 0.4 + 0.2 * buys);
			String rationale = buys + " insider purchase(s) in " + INSIDER_WINDOW_DAYS + "d"
					+ (sells > 0 ? ", " + sells + " sale(s)" : "");
			return Optional.of(new AgentSignal("agent-4-financial", SignalDirection.BULLISH, weight, rationale));
		}
		double weight = Math.min(0.35, 0.1 + 0.05 * sells); // sales are noisy → low influence
		return Optional.of(new AgentSignal("agent-4-financial", SignalDirection.BEARISH, weight,
				sells + " insider sale(s) in " + INSIDER_WINDOW_DAYS + "d (often routine)"));
	}

	/** Agent 2 — crowd sentiment: net bullish/bearish skew, weighted by post volume and conviction. */
	private Optional<AgentSignal> socialSignal(String ticker) {
		long bull = 0;
		long bear = 0;
		long total = 0;
		for (Object[] row : social.sentimentCountsForTicker(ticker, Instant.now().minus(SOCIAL_WINDOW))) {
			SentimentLabel label = (SentimentLabel) row[0];
			long count = ((Number) row[1]).longValue();
			total += count;
			if (label == SentimentLabel.BULLISH) {
				bull += count;
			}
			else if (label == SentimentLabel.BEARISH) {
				bear += count;
			}
		}
		long scored = bull + bear;
		if (total < SOCIAL_MIN_POSTS || scored == 0) {
			return Optional.empty(); // too little crowd signal to be meaningful
		}
		double net = (double) (bull - bear) / scored; // -1 (all bearish) .. +1 (all bullish)
		SignalDirection dir = net > SIGNAL_DIRECTION_DEADZONE ? SignalDirection.BULLISH
				: net < -SIGNAL_DIRECTION_DEADZONE ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double volume = Math.min(1.0, (double) total / SOCIAL_FULL_VOLUME);
		double weight = SOCIAL_MAX_WEIGHT * volume * (0.4 + 0.6 * Math.abs(net));
		String rationale = String.format("Crowd: %d bullish / %d bearish of %d posts (net %+.0f%%)",
				bull, bear, total, net * 100);
		return Optional.of(new AgentSignal("agent-2-social", dir, weight, rationale));
	}

	private Optional<AgentSignal> newsSignal(String ticker) {
		List<NewsArticle> articles = news.findAnalyzedForTicker(ticker, Instant.now().minus(NEWS_WINDOW));
		if (articles.isEmpty()) {
			return Optional.empty();
		}
		// The same story arrives via Finnhub, GDELT AND RSS — counting raw articles inflates both the
		// coverage weight and the sentiment average with duplicates. Cluster by normalized headline and
		// score over one representative per distinct story (Fable 5 follow-up).
		List<NewsArticle> stories = clusterByHeadline(articles);
		double avgSentiment = average(stories, NewsArticle::getSentimentScore);
		double avgRelevance = average(stories, NewsArticle::getRelevanceScore);
		SignalDirection dir = avgSentiment > 0.1 ? SignalDirection.BULLISH
				: avgSentiment < -0.1 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double coverage = Math.min(1.0, (double) stories.size() / FULL_COVERAGE_ARTICLES);
		double weight = coverage * (0.5 + 0.5 * avgRelevance);
		String rationale = String.format(
				"Avg news sentiment %.2f across %d distinct stor%s (%d article%s), relevance %.0f%%",
				avgSentiment, stories.size(), stories.size() == 1 ? "y" : "ies",
				articles.size(), articles.size() == 1 ? "" : "s", avgRelevance * 100);
		return Optional.of(new AgentSignal("agent-1-news", dir, weight, rationale));
	}

	/**
	 * Agent 8 — macro/political news (tariffs, Fed policy, executive orders) that moves every held
	 * ticker at once rather than one company specifically. Same headline-dedup + coverage/relevance
	 * shape as {@link #newsSignal}, but reading {@link com.argus.intelligence.MacroRelevanceTagger
	 * MACRO}-tagged articles instead of ticker-tagged ones — so it's identical across every ticker in
	 * one gather cycle by design, not a per-ticker read. A distinct agent identity (not folded into
	 * Agent 1) so the reliability system can tell whether macro coverage is actually predictive
	 * independently of company-specific news, which has its own, separately mediocre track record.
	 */
	private Optional<AgentSignal> macroSignal() {
		List<NewsArticle> articles =
				news.findAnalyzedForTicker(MacroRelevanceTagger.MACRO_TAG, Instant.now().minus(MACRO_WINDOW));
		if (articles.isEmpty()) {
			return Optional.empty();
		}
		List<NewsArticle> stories = clusterByHeadline(articles);
		double avgSentiment = average(stories, NewsArticle::getSentimentScore);
		double avgRelevance = average(stories, NewsArticle::getRelevanceScore);
		SignalDirection dir = avgSentiment > 0.1 ? SignalDirection.BULLISH
				: avgSentiment < -0.1 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double coverage = Math.min(1.0, (double) stories.size() / MACRO_FULL_COVERAGE);
		double weight = MACRO_MAX_WEIGHT * coverage * (0.5 + 0.5 * avgRelevance);
		String rationale = String.format(
				"Macro/political: avg sentiment %.2f across %d distinct stor%s (%d article%s), relevance %.0f%%",
				avgSentiment, stories.size(), stories.size() == 1 ? "y" : "ies",
				articles.size(), articles.size() == 1 ? "" : "s", avgRelevance * 100);
		return Optional.of(new AgentSignal("agent-8-macro", dir, weight, rationale));
	}

	/**
	 * Collapse duplicate coverage of the same story into one representative article per cluster.
	 * Clustering key: the headline lowercased, stripped of everything non-alphanumeric, truncated to
	 * {@value #HEADLINE_KEY_LENGTH} chars — so case/punctuation/source-suffix variants of one story
	 * match, while genuinely different stories don't. The highest-relevance article represents its
	 * cluster (it carries the best analysis of that story).
	 */
	static List<NewsArticle> clusterByHeadline(List<NewsArticle> articles) {
		java.util.Map<String, NewsArticle> byKey = new java.util.LinkedHashMap<>();
		for (NewsArticle a : articles) {
			String key = headlineKey(a.getHeadline());
			NewsArticle current = byKey.get(key);
			if (current == null || relevance(a) > relevance(current)) {
				byKey.put(key, a);
			}
		}
		return List.copyOf(byKey.values());
	}

	private static final int HEADLINE_KEY_LENGTH = 60;

	private static String headlineKey(String headline) {
		String norm = (headline == null ? "" : headline)
				.toLowerCase(java.util.Locale.ROOT)
				.replaceAll("[^a-z0-9]", "");
		return norm.length() <= HEADLINE_KEY_LENGTH ? norm : norm.substring(0, HEADLINE_KEY_LENGTH);
	}

	private static double relevance(NewsArticle a) {
		return a.getRelevanceScore() == null ? 0 : a.getRelevanceScore().doubleValue();
	}

	/** Most-recent candles in chronological order — {@link TechnicalIndicators} and {@link
	 * PriceCandleRepository}'s own "recent N" query convention both need ascending order, but the
	 * repository (matching every other "recent N" query in the app) returns newest-first. Fetched
	 * once per {@link #gather} call and shared by {@link #technicalSignal} and {@link #causeSignal}
	 * so a single gather doesn't hit the candles table twice. */
	private List<PriceCandle> ascendingCandles(String ticker) {
		List<PriceCandle> recent = candles.findTop200ByTickerOrderByCandleDateDesc(ticker);
		List<PriceCandle> ascending = new ArrayList<>(recent);
		Collections.reverse(ascending);
		return ascending;
	}

	/**
	 * Agent 10 — deterministic technical read, two families of evidence (no LLM involved anywhere,
	 * same "no number comes from an LLM" discipline as every other source here):
	 * <ul>
	 *   <li><b>Mean-reversion</b> — RSI(14) extremes (below 30 oversold/bullish-lean, above 70
	 *       overbought/bearish-lean) corroborated by Bollinger %B (ta4j-backed): below the lower
	 *       band reads the same direction as oversold RSI, above the upper band the same as
	 *       overbought. The two are averaged when both fire, so Bollinger can also trigger a read on
	 *       its own when RSI alone sits inside its deadzone.</li>
	 *   <li><b>Trend</b> — whether price sits above/below its 20/50-day SMA, plus the MACD histogram
	 *       sign (ta4j-backed: positive = the MACD line running above its own signal line, bullish
	 *       momentum) as a third vote in the same average.</li>
	 * </ul>
	 * Mean-reversion dominates when it fires (an extreme is a stronger, rarer read); with no extreme,
	 * the trend vote alone is a weaker corroborating read.
	 */
	private Optional<AgentSignal> technicalSignal(List<PriceCandle> ascending) {
		if (ascending.isEmpty()) {
			return Optional.empty();
		}
		Optional<TechnicalIndicators.Snapshot> snapOpt = TechnicalIndicators.snapshot(ascending);
		if (snapOpt.isEmpty()) {
			return Optional.empty();
		}
		TechnicalIndicators.Snapshot snap = snapOpt.get();
		double rsi = snap.rsi14().doubleValue();
		double rsiSignal = rsi < 30 ? (30 - rsi) / 30.0
				: rsi > 70 ? -(rsi - 70) / 30.0
				: 0.0;

		Double pctB = snap.bollingerPercentB();
		double bollingerSignal = 0.0;
		if (pctB != null) {
			if (pctB < 0) {
				bollingerSignal = Math.min(1.0, -pctB); // below the lower band -> bullish (oversold-style)
			}
			else if (pctB > 1) {
				bollingerSignal = -Math.min(1.0, pctB - 1); // above the upper band -> bearish (overbought-style)
			}
		}
		double meanReversionSignal = (rsiSignal != 0 && bollingerSignal != 0) ? (rsiSignal + bollingerSignal) / 2.0
				: rsiSignal != 0 ? rsiSignal : bollingerSignal;

		double trendSum = 0;
		int trendVotes = 0;
		if (snap.sma20() != null) {
			trendSum += snap.lastClose().compareTo(snap.sma20()) > 0 ? 1 : -1;
			trendVotes++;
		}
		if (snap.sma50() != null) {
			trendSum += snap.lastClose().compareTo(snap.sma50()) > 0 ? 1 : -1;
			trendVotes++;
		}
		if (snap.macdHistogram() != null) {
			trendSum += snap.macdHistogram() > 0 ? 1 : -1;
			trendVotes++;
		}
		double trendNet = trendVotes == 0 ? 0 : trendSum / trendVotes;

		// Mean-reversion extremes dominate when present; with no extreme, the trend vote alone is a
		// weaker read.
		double net = meanReversionSignal != 0 ? meanReversionSignal : trendNet * 0.4;
		if (Math.abs(net) < SIGNAL_DIRECTION_DEADZONE) {
			return Optional.empty();
		}
		SignalDirection dir = net > 0 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		double weight = TECHNICAL_MAX_WEIGHT * Math.min(1.0, Math.abs(net));
		String rationale = String.format("RSI %.1f%s%s, price %s its trend%s", rsi,
				rsi < 30 ? " (oversold)" : rsi > 70 ? " (overbought)" : "",
				pctB != null && (pctB < 0 || pctB > 1)
						? String.format(", Bollinger %%B %.2f (%s band)", pctB, pctB < 0 ? "below" : "above") : "",
				trendNet > 0 ? "above" : trendNet < 0 ? "below" : "mixed vs.",
				snap.macdHistogram() != null
						? String.format(", MACD %s", snap.macdHistogram() > 0 ? "bullish" : "bearish") : "");
		return Optional.of(new AgentSignal("agent-10-technical", dir, weight, rationale));
	}

	/**
	 * Agent 11 — only fires on a real, meaningful drawdown ({@code
	 * argus.technical.cause-trigger-drawdown-pct}), then classifies why via {@link
	 * CauseClassificationService}. When the classification reads MACRO_EXTERNAL and temporary with
	 * confidence clearing {@code argus.technical.cause-confidence-floor}, emits a BULLISH signal
	 * whose weight is computed here from that confidence (never the LLM's own number) — this is the
	 * concrete "buy the dip" mechanism the rest of the app can weigh against News/Social like any
	 * other source. Silent (never emitted) on a shallow dip, a low-confidence read, or a
	 * COMPANY_SPECIFIC classification — same "silence over a wrong guess" discipline as every other
	 * source here.
	 */
	private Optional<AgentSignal> causeSignal(String ticker, List<PriceCandle> ascending) {
		if (ascending.isEmpty()) {
			return Optional.empty();
		}
		Optional<Double> drawdown =
				TechnicalIndicators.drawdownFromHighPct(ascending, TechnicalIndicators.DRAWDOWN_LOOKBACK_DAYS);
		if (drawdown.isEmpty() || drawdown.get() > technicalProps.causeTriggerDrawdownPct()) {
			return Optional.empty(); // no drawdown, or too shallow to be worth an LLM call
		}
		Optional<CauseClassificationService.Classification> classified =
				causeClassification.classify(ticker, drawdown.get());
		if (classified.isEmpty()) {
			return Optional.empty();
		}
		CauseClassificationService.Classification c = classified.get();
		if (c.cause() != CauseClassificationService.Cause.MACRO_EXTERNAL || !c.temporary()
				|| c.confidence() < technicalProps.causeConfidenceFloor()) {
			return Optional.empty();
		}
		double weight = CAUSE_MAX_WEIGHT * c.confidence();
		String rationale = String.format("%.1f%% below 60-day high, classified macro-driven & temporary "
				+ "(%.0f%% confidence): %s", drawdown.get(), c.confidence() * 100, c.reasoning());
		alertPossibleDipBuy(ticker, drawdown.get(), c);
		return Optional.of(new AgentSignal("agent-11-cause", SignalDirection.BULLISH, weight, rationale));
	}

	/** Surfaces the dip-buy thesis directly, not just as one more line in a recommendation's signal
	 * breakdown — the user explicitly asked to "visualize" a temporary dip as it's detected.
	 * Best-effort: a push failure must never stop the underlying signal from being returned. */
	private void alertPossibleDipBuy(String ticker, double drawdownPct, CauseClassificationService.Classification c) {
		try {
			notifications.notify(Notification.forTicker(UrgencyTier.IMPORTANT, ticker, "BULLISH",
					c.confidence(), 1.0, "📉 Possible buying opportunity: " + ticker,
					String.format("%.1f%% below its 60-day high — looks macro-driven and temporary, "
							+ "not company-specific: %s", drawdownPct, c.reasoning()),
					"/recommendations"));
		}
		catch (RuntimeException ex) {
			log.warn("Dip-buy alert for {} failed: {}", ticker, ex.getMessage());
		}
	}

	private Optional<AgentSignal> calendarSignal(String ticker) {
		QuietPeriodStatus qp = quietPeriod.statusFor(ticker);
		if (qp.status() == QuietPeriodStatus.Status.NOTE) {
			return Optional.of(new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 0.3,
					"Earnings within " + qp.tradingDaysUntil() + " trading days — added uncertainty"));
		}
		return Optional.empty();
	}

	private static double average(List<NewsArticle> articles,
			java.util.function.Function<NewsArticle, java.math.BigDecimal> field) {
		return articles.stream().map(field).filter(java.util.Objects::nonNull)
				.mapToDouble(java.math.BigDecimal::doubleValue).average().orElse(0);
	}
}
