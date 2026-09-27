package com.argus.deepanalysis;

import com.argus.filings.FilingDigest;
import com.argus.filings.FilingDigestService;
import com.argus.notification.Notification;
import com.argus.notification.NotificationService;
import com.argus.notification.UrgencyTier;
import com.argus.technical.ChartStudyService;
import com.argus.technical.PriceCandle;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Agent 11's slow verdicts honest between runs. Each hour it re-checks every standing directional verdict against the
 * latest price and any newer filing ({@link ThesisCheck}); one that has been undermined is flagged AT_RISK — which the
 * recommender reads as a caveat and a conviction haircut and the Investor reads as a reason to close — and a fresh analysis is
 * queued straight away so the verdict is re-decided in hours, not at the next nightly pass. Cheap: no model calls here.
 */
@Component
public class ThesisTracker {

	private static final Logger log = LoggerFactory.getLogger(ThesisTracker.class);

	private final DeepAnalysisRepository repository;
	private final ChartStudyService charts;
	private final FilingDigestService filings;
	private final DeepAnalysisRunner runner;
	private final NotificationService notifications;
	private final DeepAnalysisProperties props;

	public ThesisTracker(DeepAnalysisRepository repository, ChartStudyService charts, FilingDigestService filings, DeepAnalysisRunner runner,
			NotificationService notifications, DeepAnalysisProperties props) {
		this.repository = repository;
		this.charts = charts;
		this.filings = filings;
		this.runner = runner;
		this.notifications = notifications;
		this.props = props;
	}

	@Scheduled(cron = "0 17 * * * *", zone = "America/Toronto")
	public void hourly() {
		if (!props.enabled()) {
			return;
		}
		try {
			int flagged = check();
			if (flagged > 0) {
				log.info("Agent 11: thesis tracker flagged {} verdict(s) AT_RISK", flagged);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: thesis tracker failed: {}", ex.getMessage());
		}
	}

	/** One pass; returns how many verdicts were newly flagged. Package-visible for tests. */
	int check() {
		Instant now = Instant.now();
		Map<String, FilingDigest> newestFiling = filings.latestPerTicker().stream()
				.collect(Collectors.toMap(FilingDigest::getTicker, Function.identity(), (a, b) -> a));
		int flagged = 0;
		for (DeepAnalysis d : repository.latestDonePerTicker()) {
			if (d.getVerdict() == null || d.getVerdict() == DeepVerdict.WAIT || d.isAtRisk()) continue;
			if (d.getExpiresAt() != null && d.getExpiresAt().isBefore(now)) continue;
			Double last = lastClose(d.getTicker());
			FilingDigest f = newestFiling.get(d.getTicker());
			var reason = ThesisCheck.atRisk(d.getVerdict(), d.getFinishedAt().atZone(ZoneOffset.UTC).toLocalDate(),
					d.getInvalidationPrice() == null ? null : d.getInvalidationPrice().doubleValue(), last,
					f == null ? null : f.getFiledAt(), f == null ? null : f.getScore(), f == null ? null : f.getGuidance());
			if (reason.isEmpty()) {
				d.markChecked();
				repository.save(d);
				continue;
			}
			d.flagAtRisk(reason.get());
			repository.save(d);
			flagged++;
			runner.enqueue(d.getTicker(), "THESIS");
			notifications.notify(Notification.forTicker(UrgencyTier.IMPORTANT, d.getTicker(), d.getVerdict() == DeepVerdict.WORTH_BUYING ? "BEARISH" : "BULLISH",
					0.8, 1.0, d.getTicker() + ": deep-analysis thesis at risk", reason.get() + " Re-analysis queued.", "/intelligence"));
		}
		return flagged;
	}

	private Double lastClose(String ticker) {
		List<PriceCandle> recent = charts.recentCandles(ticker, 1);
		return recent.isEmpty() ? null : recent.get(recent.size() - 1).getClose().doubleValue();
	}
}
