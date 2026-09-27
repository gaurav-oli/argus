package com.argus.recommendation;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.deepanalysis.DeepAnalysis;
import com.argus.deepanalysis.DeepAnalysisRepository;
import com.argus.deepanalysis.DeepVerdict;
import com.argus.filings.FilingDigestService;
import com.argus.filings.FilingView;
import com.argus.fundamentals.Fundamentals;
import com.argus.fundamentals.FundamentalsService;
import com.argus.regime.MacroTheme;
import com.argus.regime.MarketRegime;
import com.argus.regime.MarketRegimeService;
import com.argus.regime.Sector;
import com.argus.regime.SectorClassifier;
import com.argus.sec.SecFiling;
import com.argus.sec.SecFilingRepository;
import com.argus.social.SocialPostRepository;
import com.argus.technical.ChartStudy;
import com.argus.technical.ChartStudyService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
	/** Average sentiment magnitude at which news reaches its full conviction weight. */
	private static final double NEWS_FULL_CONVICTION_SENTIMENT = 0.35;
	/** Macro/political news cycles and market reactions move faster than company-specific coverage. */
	private static final Duration MACRO_WINDOW = Duration.ofDays(3);
	private static final int MACRO_FULL_COVERAGE = 3;
	/** Unproven signal (freshly admitted) — capped below news until its own track record earns more. */
	private static final double MACRO_MAX_WEIGHT = 0.5;
	private static final Duration SOCIAL_WINDOW = Duration.ofDays(3);
	private static final int SOCIAL_MIN_POSTS = 5;
	private static final int SOCIAL_FULL_VOLUME = 30;
	/**
	 * Crowd sentiment is noisier than curated news — cap its max influence well below Agent 1's. Halved
	 * from 0.6: in the live paper book 100 of the crowd's 102 directional calls were bullish and those
	 * names lagged SPY by ~1.5% over 7 days, i.e. it added a one-way bullish tilt, not information.
	 */
	private static final double SOCIAL_MAX_WEIGHT = 0.3;
	/** Net crowd skew above this on real volume is euphoria, read as a contrarian caution, not a buy. */
	private static final double SOCIAL_EUPHORIA_NET = 0.6;
	/** A broad selloff day mutes macro-headline weight: shock headlines tend to fade within days. */
	private static final double MACRO_SHOCK_MUTE = 0.4;

	private static final int INSIDER_WINDOW_DAYS = 30;

	private static final Duration INTERNET_WINDOW = Duration.ofDays(14);
	private static final Duration INTERNET_RECENT = Duration.ofDays(3);
	private static final double ATTENTION_SPIKE = 1.3;
	/** Internet buzz is the noisiest, lowest-ROI source — hard-cap its influence below all others. */
	private static final double INTERNET_MAX_WEIGHT = 0.35;

	/** Chart study (Agent 10): candlesticks, volume, trend, levels and relative strength blended into one
	 * read — a supporting/corroborating signal, same order of magnitude as News. */
	private static final double TECHNICAL_MAX_WEIGHT = 0.7;
	/** The chart score must clear this magnitude before Agent 10 calls a direction. */
	private static final double TECHNICAL_DEADZONE = 0.2;
	/** Fundamentals (Agent 12) move slowly — a supporting signal, capped below News. */
	private static final double FUNDAMENTAL_MAX_WEIGHT = 0.6;
	private static final double FUNDAMENTAL_DEADZONE = 0.2;
	/** Filings (Agent 14) are hard company evidence, but only fresh ones count. */
	private static final double FILINGS_MAX_WEIGHT = 0.7;
	private static final double FILINGS_DEADZONE = 0.25;
	private static final long FILINGS_MAX_AGE_DAYS = 45;
	private static final java.time.Duration FUNDAMENTALS_MAX_AGE = java.time.Duration.ofDays(14);
	/** Agent 11's deep verdict is the slowest, most-considered signal and may carry the most weight — the
	 * scoring policy still caps any single signal's contribution to conviction. */
	private static final double DEEP_MAX_WEIGHT = 1.2;

	private final NewsArticleRepository news;
	private final SocialPostRepository social;
	private final SecFilingRepository sec;
	private final WebMentionRepository web;
	private final EarningsQuietPeriodService quietPeriod;
	private final AdaptiveTuningService tuning;
	private final ChartStudyService chartStudies;
	private final FundamentalsService fundamentals;
	private final FilingDigestService filings;
	private final DeepAnalysisRepository deepAnalyses;
	private final SectorClassifier sectors;
	private final MarketRegimeService regimes;

	public AgentSignalGatherer(NewsArticleRepository news, SocialPostRepository social,
			SecFilingRepository sec, WebMentionRepository web, EarningsQuietPeriodService quietPeriod,
			AdaptiveTuningService tuning, ChartStudyService chartStudies, FundamentalsService fundamentals,
			DeepAnalysisRepository deepAnalyses, SectorClassifier sectors, MarketRegimeService regimes, FilingDigestService filings) {
		this.news = news;
		this.social = social;
		this.sec = sec;
		this.web = web;
		this.quietPeriod = quietPeriod;
		this.tuning = tuning;
		this.chartStudies = chartStudies;
		this.fundamentals = fundamentals;
		this.filings = filings;
		this.deepAnalyses = deepAnalyses;
		this.sectors = sectors;
		this.regimes = regimes;
	}

	/**
	 * Every quick agent's signal for {@code ticker} — news, macro, crowd, insider, web, calendar, the chart
	 * study (Agent 10) and fundamentals (Agent 12) — each scaled by its learned reliability. This is also
	 * the evidence base Agent 11 reasons over; it deliberately excludes Agent 11's own verdict.
	 */
	public List<AgentSignal> gatherBase(String ticker) {
		List<AgentSignal> signals = new ArrayList<>();
		newsSignal(ticker).ifPresent(signals::add);
		macroSignal(ticker).ifPresent(signals::add);
		socialSignal(ticker).ifPresent(signals::add);
		insiderSignal(ticker).ifPresent(signals::add);
		internetSignal(ticker).ifPresent(signals::add);
		calendarSignal(ticker).ifPresent(signals::add);
		chartStudies.studyFor(ticker).ifPresent(study -> technicalSignal(study).ifPresent(signals::add));
		fundamentalSignal(ticker).ifPresent(signals::add);
		filingSignal(ticker).map(this::applyReliability).ifPresent(signals::add);
		// Phase B: scale each agent's weight by its learned reliability (identity when tuning is disabled).
		return signals.stream().map(this::applyReliability).toList();
	}

	/** The full signal set for a recommendation: {@link #gatherBase} plus Agent 11's stored deep verdict, if fresh. */
	public List<AgentSignal> gather(String ticker) {
		List<AgentSignal> tuned = new ArrayList<>(gatherBase(ticker));
		deepSignal(ticker).map(this::applyReliability).ifPresent(tuned::add);
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
		return new AgentSignal(s.agent(), s.direction(), s.weight() * mult, s.rationale(), s.horizonHintDays());
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
		return Optional.of(new AgentSignal("agent-3-internet", dir, weight, rationale, 7));
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
			return Optional.of(new AgentSignal("agent-4-financial", SignalDirection.BULLISH, weight, rationale, 90));
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
		if (net > SOCIAL_EUPHORIA_NET && total >= SOCIAL_FULL_VOLUME / 2) {
			// A near-unanimous bullish crowd is what tops look like, and here it has led to
			// underperformance — surface it as a caution rather than a vote for the call.
			dir = SignalDirection.NEUTRAL;
			rationale += " — euphoric crowd, treated as contrarian caution";
		}
		return Optional.of(new AgentSignal("agent-2-social", dir, weight, rationale, 5));
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
		// Scale by how strong the sentiment actually is: a barely-positive 0.11 average across many stories
		// used to carry the same weight as a decisive 0.6 (weight was coverage × relevance only).
		double conviction = Math.min(1.0, Math.abs(avgSentiment) / NEWS_FULL_CONVICTION_SENTIMENT);
		double weight = coverage * (0.5 + 0.5 * avgRelevance) * conviction;
		String rationale = String.format(
				"Avg news sentiment %.2f across %d distinct stor%s (%d article%s), relevance %.0f%%",
				avgSentiment, stories.size(), stories.size() == 1 ? "y" : "ies",
				articles.size(), articles.size() == 1 ? "" : "s", avgRelevance * 100);
		return Optional.of(new AgentSignal("agent-1-news", dir, weight, rationale, 10));
	}

	/**
	 * Agent 8 — macro/political news (tariffs, Fed policy, oil shocks, executive orders) that moves the
	 * market rather than one company. Same headline-dedup + coverage/relevance shape as {@link
	 * #newsSignal}, reading {@link com.argus.intelligence.MacroRelevanceTagger MACRO}-tagged articles —
	 * but <b>sector-aware</b>: each story is classified into {@link MacroTheme}s and its sentiment scaled
	 * by the ticker's sector exposure, so a tariff story reads bearish for a chipmaker and near-neutral
	 * for a utility, and an oil spike can read bullish for an energy name. Kept a distinct agent identity
	 * so the reliability system can judge macro coverage independently of company-specific news.
	 */
	private Optional<AgentSignal> macroSignal(String ticker) {
		List<NewsArticle> articles =
				news.findAnalyzedForTicker(MacroRelevanceTagger.MACRO_TAG, Instant.now().minus(MACRO_WINDOW));
		if (articles.isEmpty()) {
			return Optional.empty();
		}
		List<NewsArticle> stories = clusterByHeadline(articles);
		Sector sector = sectors.sectorOf(ticker);
		// Sector-aware read: each story's market-wide sentiment is scaled by how exposed THIS sector is
		// to that kind of story (MacroTheme sensitivity, sign-aware — an oil shock is good for energy,
		// a yield spike is bad for growth but not for banks), instead of one identical number per ticker.
		double adjustedSum = 0;
		int counted = 0;
		java.util.Map<MacroTheme, Integer> themeCounts = new java.util.EnumMap<>(MacroTheme.class);
		for (NewsArticle a : stories) {
			if (a.getSentimentScore() == null) {
				continue;
			}
			List<MacroTheme> themes = MacroTheme.classify(a.getHeadline() + " " + a.getSummary());
			themes.forEach(t -> themeCounts.merge(t, 1, Integer::sum));
			adjustedSum += a.getSentimentScore().doubleValue() * MacroTheme.meanSensitivity(themes, sector);
			counted++;
		}
		if (counted == 0) {
			return Optional.empty();
		}
		double avgAdjusted = adjustedSum / counted;
		double avgRelevance = average(stories, NewsArticle::getRelevanceScore);
		SignalDirection dir = avgAdjusted > 0.05 ? SignalDirection.BULLISH
				: avgAdjusted < -0.05 ? SignalDirection.BEARISH : SignalDirection.NEUTRAL;
		double coverage = Math.min(1.0, (double) stories.size() / MACRO_FULL_COVERAGE);
		double strength = Math.min(1.0, Math.abs(avgAdjusted) / 0.25);
		double weight = MACRO_MAX_WEIGHT * coverage * (0.5 + 0.5 * avgRelevance) * strength;

		String muted = "";
		MarketRegime regime = regimes.current();
		if (regime.broadSelloff()) {
			weight *= MACRO_SHOCK_MUTE;
			muted = " — muted: broad one-day selloff, shock headlines tend to fade";
		}
		String topThemes = themeCounts.entrySet().stream()
				.sorted(java.util.Map.Entry.<MacroTheme, Integer>comparingByValue().reversed()).limit(2)
				.map(e -> e.getKey().label()).reduce((x, y) -> x + ", " + y).orElse("macro");
		String rationale = String.format(
				"Macro (%s) as it hits %s: sector-adjusted sentiment %+.2f across %d distinct stor%s, relevance %.0f%%%s",
				topThemes, sector.label().toLowerCase(), avgAdjusted, stories.size(),
				stories.size() == 1 ? "y" : "ies", avgRelevance * 100, muted);
		return Optional.of(new AgentSignal("agent-8-macro", dir, weight, rationale, 7));
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

	/**
	 * Agent 10 — the chart study ({@link com.argus.technical.ChartReader}): trend against the 20/50/200-day
	 * averages, RSI/MACD/Bollinger momentum, volume accumulation vs distribution, nearest support and
	 * resistance, candlestick reversal patterns read in context, and strength relative to the S&amp;P 500.
	 * Deterministic — no LLM — and direction-neutral inside the deadzone. Weight scales with how decisive
	 * the blended score is. A trend-led read is a slower edge than a mean-reversion/pattern one, which
	 * feeds the recommended holding period.
	 */
	private Optional<AgentSignal> technicalSignal(ChartStudy study) {
		double score = study.score();
		if (Math.abs(score) < TECHNICAL_DEADZONE) {
			return Optional.empty();
		}
		SignalDirection dir = score > 0 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		double weight = TECHNICAL_MAX_WEIGHT * Math.min(1.0, Math.abs(score) / 0.7);
		boolean trendLed = study.trend() != ChartStudy.Trend.SIDEWAYS && study.sma200() != null;
		String rationale = study.notes().stream()
				.filter(n -> n.startsWith("Trend:") || n.startsWith("Candlestick (") || n.startsWith("Relative strength")
						|| n.startsWith("Volume:"))
				.limit(4).reduce((x, y) -> x + " " + y).orElse("Chart study " + study.bias());
		return Optional.of(new AgentSignal("agent-10-technical", dir, weight,
				String.format("Chart %s (score %+.2f): %s", study.bias().toLowerCase(), score, rationale),
				trendLed ? 30 : 10));
	}

	/**
	 * Agent 12 — fundamentals, from the stored snapshot (never a network call on this path). Growth,
	 * margins, balance sheet, earnings surprises, analyst consensus and valuation vs peers, scored
	 * deterministically by {@link com.argus.fundamentals.FundamentalsAnalyzer}. ETFs and unrecognised
	 * symbols have no company data and emit nothing. Fundamentals move over quarters, so this is the
	 * signal that argues for a long hold.
	 */
	private Optional<AgentSignal> fundamentalSignal(String ticker) {
		Optional<Fundamentals> f = fundamentals.latestFresh(ticker, FUNDAMENTALS_MAX_AGE);
		if (f.isEmpty() || !f.get().applicable() || Math.abs(f.get().score()) < FUNDAMENTAL_DEADZONE) {
			return Optional.empty();
		}
		double score = f.get().score();
		SignalDirection dir = score > 0 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		double weight = FUNDAMENTAL_MAX_WEIGHT * Math.min(1.0, Math.abs(score) / 0.6);
		String rationale = f.get().notes().stream().limit(3).reduce((x, y) -> x + " " + y).orElse("Fundamentals " + f.get().bias());
		return Optional.of(new AgentSignal("agent-12-fundamental", dir, weight,
				String.format("Fundamentals %s (score %+.2f): %s", f.get().bias().toLowerCase(), score, rationale), 90));
	}

	/** Agent 14 — the newest earnings release / 10-Q / 10-K read (guidance, tone, going-concern), from stored digests only. */
	private Optional<AgentSignal> filingSignal(String ticker) {
		Optional<FilingView> v;
		try {
			v = filings.view(ticker);
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
		if (v.isEmpty() || v.get().ageDays() > FILINGS_MAX_AGE_DAYS || Math.abs(v.get().score()) < FILINGS_DEADZONE) {
			return Optional.empty();
		}
		double score = v.get().score();
		SignalDirection dir = score > 0 ? SignalDirection.BULLISH : SignalDirection.BEARISH;
		double weight = FILINGS_MAX_WEIGHT * Math.min(1.0, Math.abs(score) / 0.6);
		String guide = v.get().guidance() == null || "NONE".equals(v.get().guidance()) ? "" : ", guidance " + v.get().guidance().toLowerCase();
		return Optional.of(new AgentSignal("agent-14-filings", dir, weight,
				String.format("Filings %s (score %+.2f%s, %d days ago): %s", score > 0 ? "positive" : "negative", score, guide, v.get().ageDays(),
						v.get().headline() == null ? "" : v.get().headline()), 30));
	}

	/**
	 * What the standing evidence says about valuation and guidance, for the policy's feature tokens and prose (the situation a
	 * call is made in, so the Trade Learner can later mine outcomes by it). Never throws; both parts optional.
	 */
	public RecommendationPolicy.Standing standing(String ticker) {
		String guidance = null, valuation = null;
		try {
			guidance = filings.view(ticker).filter(v -> v.ageDays() <= FILINGS_MAX_AGE_DAYS).map(FilingView::guidance)
					.filter(g -> !"NONE".equals(g)).orElse(null);
		}
		catch (RuntimeException ex) {
			// tokens are optional
		}
		try {
			valuation = fundamentals.latestFresh(ticker, FUNDAMENTALS_MAX_AGE).map(Fundamentals::valuation)
					.map(com.argus.fundamentals.Fundamentals.ValuationView::verdict).orElse(null);
		}
		catch (RuntimeException ex) {
			// tokens are optional
		}
		return new RecommendationPolicy.Standing(guidance, valuation);
	}

	/**
	 * Agent 11 — the deep analyst's stored verdict, if one finished recently enough. The analysis itself
	 * takes minutes to hours (several LLM passes over a full evidence pack), so it runs in the background
	 * and this hot path only reads its result. WAIT is not a signal (the policy still sees it as a
	 * caveat); a decisive verdict carries weight in proportion to its guard-checked conviction.
	 */
	private Optional<AgentSignal> deepSignal(String ticker) {
		Optional<DeepAnalysis> latest = deepAnalyses.findFirstByTickerAndStatusOrderByFinishedAtDesc(ticker, DeepAnalysis.Status.DONE);
		if (latest.isEmpty() || latest.get().getExpiresAt() == null || latest.get().getExpiresAt().isBefore(Instant.now())) {
			return Optional.empty();
		}
		DeepAnalysis d = latest.get();
		if (d.getVerdict() == null || d.getVerdict() == DeepVerdict.WAIT || d.getConviction() == null) {
			return Optional.empty();
		}
		if (d.isAtRisk()) {
			// The thesis tracker saw the verdict broken (price through its invalidation level, or a contradicting filing):
			// don't keep leaning on it while the re-analysis runs.
			return Optional.empty();
		}
		boolean buy = d.getVerdict() == DeepVerdict.WORTH_BUYING;
		double weight = DEEP_MAX_WEIGHT * d.getConviction() / 100.0;
		String rationale = String.format("Deep analysis (%s): %s%s", d.getVerdict().label().toLowerCase(),
				d.getHeadline() == null ? "" : d.getHeadline(), buy && d.getHoldDays() != null ? " — hold ~" + d.getHoldDays() + " days" : "");
		return Optional.of(new AgentSignal("agent-11-deep", buy ? SignalDirection.BULLISH : SignalDirection.BEARISH, weight, rationale,
				buy && d.getHoldDays() != null ? d.getHoldDays() : 14));
	}

	private Optional<AgentSignal> calendarSignal(String ticker) {
		QuietPeriodStatus qp = quietPeriod.statusFor(ticker);
		if (qp.status() == QuietPeriodStatus.Status.NOTE) {
			return Optional.of(new AgentSignal("agent-7-calendar", SignalDirection.BEARISH, 0.3,
					"Earnings within " + qp.tradingDaysUntil() + " trading days — added uncertainty", 7));
		}
		return Optional.empty();
	}

	private static double average(List<NewsArticle> articles,
			java.util.function.Function<NewsArticle, java.math.BigDecimal> field) {
		return articles.stream().map(field).filter(java.util.Objects::nonNull)
				.mapToDouble(java.math.BigDecimal::doubleValue).average().orElse(0);
	}
}
