package com.argus.deepanalysis;

import com.argus.calendar.EarningsQuietPeriodService;
import com.argus.calendar.QuietPeriodStatus;
import com.argus.fundamentals.Fundamentals;
import com.argus.fundamentals.FundamentalsService;
import com.argus.intelligence.MacroRelevanceTagger;
import com.argus.intelligence.NewsArticle;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.intelligence.SentimentLabel;
import com.argus.internet.WebMention;
import com.argus.internet.WebMentionRepository;
import com.argus.portfolio.LivePortfolioService;
import com.argus.recommendation.AgentSignalGatherer;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Engages every other agent and data source on behalf of Agent 11: the fast agents' signals
 * ({@link AgentSignalGatherer#gatherBase}), Agent 10's chart study, Agent 12's fundamentals (refreshed
 * live if stale — this is a slow job, so it can afford the ~10 Finnhub calls), recent company news,
 * insider filings, crowd/web attention, the earnings calendar, and the sector-aware macro picture with
 * the market regime. Pure gathering — no LLM, no scoring beyond what those agents already computed.
 * Every source is optional; a missing one becomes an honest "no data" line, never an invented one.
 */
@Component
public class EvidenceCollector {

	private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);
	private static final Duration NEWS_WINDOW = Duration.ofDays(14);
	private static final Duration MACRO_WINDOW = Duration.ofDays(3);
	private static final Duration FUNDAMENTALS_MAX_AGE = Duration.ofHours(24);
	private static final int MAX_HEADLINES = 10;
	private static final int MAX_MACRO_HEADLINES = 6;

	private final AgentSignalGatherer gatherer;
	private final ChartStudyService charts;
	private final FundamentalsService fundamentals;
	private final NewsArticleRepository news;
	private final SecFilingRepository sec;
	private final SocialPostRepository social;
	private final WebMentionRepository web;
	private final EarningsQuietPeriodService earnings;
	private final MarketRegimeService regimes;
	private final SectorClassifier sectors;
	private final LivePortfolioService prices;

	public EvidenceCollector(AgentSignalGatherer gatherer, ChartStudyService charts, FundamentalsService fundamentals,
			NewsArticleRepository news, SecFilingRepository sec, SocialPostRepository social, WebMentionRepository web,
			EarningsQuietPeriodService earnings, MarketRegimeService regimes, SectorClassifier sectors,
			LivePortfolioService prices) {
		this.gatherer = gatherer;
		this.charts = charts;
		this.fundamentals = fundamentals;
		this.news = news;
		this.sec = sec;
		this.social = social;
		this.web = web;
		this.earnings = earnings;
		this.regimes = regimes;
		this.sectors = sectors;
		this.prices = prices;
	}

	public Evidence collect(String ticker) {
		Sector sector = sectors.sectorOf(ticker);
		Optional<ChartStudy> chart = charts.studyFor(ticker);
		Optional<Fundamentals> fund = safe(() -> fundamentals.getOrRefresh(ticker, FUNDAMENTALS_MAX_AGE), "fundamentals");
		boolean etf = fund.isPresent() && !fund.get().applicable();
		Double price = prices.latestPrice(ticker).map(BigDecimal::doubleValue)
				.orElse(chart.map(ChartStudy::lastClose).orElse(null));
		QuietPeriodStatus quiet = earnings.statusFor(ticker);
		Integer earningsDays = quiet.status() == QuietPeriodStatus.Status.CLEAR ? null : quiet.tradingDaysUntil();
		MarketRegime regime = regimes.current();
		return new Evidence(ticker, sector, etf, price, chart.orElse(null), fund.orElse(null), gatherer.gatherBase(ticker),
				newsBlock(ticker), insiderBlock(ticker), crowdBlock(ticker), earningsBlock(quiet), earningsDays,
				macroBlock(sector, regime), regime);
	}

	// ---- blocks ----

	private String newsBlock(String ticker) {
		List<NewsArticle> articles = news.findAnalyzedForTicker(ticker, Instant.now().minus(NEWS_WINDOW));
		if (articles.isEmpty()) {
			return "No analysed company news in the last 14 days.\n";
		}
		double avg = articles.stream().filter(a -> a.getSentimentScore() != null).mapToDouble(a -> a.getSentimentScore().doubleValue())
				.average().orElse(0);
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d articles in 14 days, average sentiment %+.2f (-1..+1). Top headlines:%n",
				articles.size(), avg));
		articles.stream().sorted(Comparator.comparing((NewsArticle a) -> a.getRelevanceScore() == null ? 0 : a.getRelevanceScore().doubleValue())
				.reversed()).limit(MAX_HEADLINES).forEach(a -> sb.append(String.format(Locale.ROOT, "- [%s] (sentiment %s, %s) %s%n",
						a.getPublishedAt().atZone(ZoneOffset.UTC).toLocalDate(),
						a.getSentimentScore() == null ? "n/a" : String.format(Locale.ROOT, "%+.2f", a.getSentimentScore()),
						a.getSource(), a.getHeadline())));
		return sb.toString();
	}

	private String insiderBlock(String ticker) {
		List<SecFiling> filings = sec.findByTickerAndTransactionTypeInAndFiledAtAfter(ticker, List.of("BUY", "SELL"),
				LocalDate.now().minusDays(90));
		if (filings.isEmpty()) {
			return "No insider (Form 4) buys or sells in the last 90 days.\n";
		}
		long buys = filings.stream().filter(f -> "BUY".equals(f.getTransactionType())).count();
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%d insider transactions in 90 days: %d buy(s), %d sell(s). Open-market buys are rare and "
				+ "meaningful; sales are often routine (scheduled plans, tax).%n", filings.size(), buys, filings.size() - buys));
		filings.stream().sorted(Comparator.comparing(SecFiling::getFiledAt).reversed()).limit(6).forEach(f -> sb.append(String.format(
				Locale.ROOT, "- %s %s (%s): %s%s%n", f.getFiledAt(), f.getInsiderName(), f.getInsiderTitle(), f.getTransactionType(),
				f.getShares() == null ? "" : ", " + f.getShares().stripTrailingZeros().toPlainString() + " shares")));
		return sb.toString();
	}

	private String crowdBlock(String ticker) {
		long bull = 0, bear = 0, total = 0;
		for (Object[] row : social.sentimentCountsForTicker(ticker, Instant.now().minus(Duration.ofDays(7)))) {
			long count = ((Number) row[1]).longValue();
			total += count;
			if (row[0] == SentimentLabel.BULLISH) bull += count;
			else if (row[0] == SentimentLabel.BEARISH) bear += count;
		}
		List<WebMention> mentions = web.findByTickerAndPostedAtAfter(ticker, Instant.now().minus(Duration.ofDays(14)));
		String crowd = total == 0 ? "No social posts in 7 days."
				: String.format(Locale.ROOT, "Social (7 days): %d posts, %d bullish / %d bearish (net %+.0f%% of the scored posts). "
						+ "A near-unanimous bullish crowd has historically been a contrarian warning here, not confirmation.", total, bull, bear,
						bull + bear == 0 ? 0 : 100.0 * (bull - bear) / (bull + bear));
		return crowd + "\n" + (mentions.isEmpty() ? "No Hacker News / Wikipedia attention in 14 days." : mentions.size() + " web mention(s) in 14 days.") + "\n";
	}

	private static String earningsBlock(QuietPeriodStatus q) {
		if (q.status() == QuietPeriodStatus.Status.CLEAR) {
			return "No earnings release within the next 30 days.\n";
		}
		return String.format(Locale.ROOT, "Next earnings release %s — about %d trading day(s) away%s.%n", q.nextEarnings(), q.tradingDaysUntil(),
				q.status() == QuietPeriodStatus.Status.QUIET ? " (inside the quiet period: results can swamp the thesis)" : "");
	}

	private String macroBlock(Sector sector, MarketRegime regime) {
		StringBuilder sb = new StringBuilder();
		sb.append("Sector: ").append(sector.label()).append(". Market regime: ").append(regime.summary()).append(" (state ")
				.append(regime.label()).append(").\n");
		Double sectorMove = regime.sectorMove(sector);
		if (sectorMove != null) {
			sb.append(String.format(Locale.ROOT, "The %s benchmark (%s) is %+.1f%% today.%n", sector.label().toLowerCase(Locale.ROOT), sector.benchmark(), sectorMove));
		}
		List<NewsArticle> macro = news.findAnalyzedForTicker(MacroRelevanceTagger.MACRO_TAG, Instant.now().minus(MACRO_WINDOW));
		if (macro.isEmpty()) {
			return sb.append("No macro/political headlines in the last 3 days.\n").toString();
		}
		sb.append("Macro headlines (last 3 days) and how exposed THIS sector is to each theme:\n");
		macro.stream().sorted(Comparator.comparing((NewsArticle a) -> a.getRelevanceScore() == null ? 0 : a.getRelevanceScore().doubleValue())
				.reversed()).limit(MAX_MACRO_HEADLINES).forEach(a -> {
			List<MacroTheme> themes = MacroTheme.classify(a.getHeadline() + " " + a.getSummary());
			double exposure = MacroTheme.meanSensitivity(themes, sector);
			sb.append(String.format(Locale.ROOT, "- %s (sentiment %s; themes: %s; sector exposure: %s)%n", a.getHeadline(),
					a.getSentimentScore() == null ? "n/a" : String.format(Locale.ROOT, "%+.2f", a.getSentimentScore()),
					themes.stream().map(MacroTheme::label).collect(Collectors.joining(", ")), exposure(exposure)));
		});
		return sb.toString();
	}

	private static String exposure(double e) {
		return e >= 0.7 ? "high" : e >= 0.3 ? "moderate" : e > -0.3 ? "low" : "inverse — tends to benefit when the market is hurt";
	}

	private <T> Optional<T> safe(java.util.function.Supplier<Optional<T>> supplier, String what) {
		try {
			return supplier.get();
		}
		catch (RuntimeException ex) {
			log.warn("Deep analysis: {} unavailable: {}", what, ex.getMessage());
			return Optional.empty();
		}
	}
}
