package com.argus.ops;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventRepository;
import com.argus.calendar.CalendarEventType;
import com.argus.intelligence.StrangerAlert;
import com.argus.intelligence.StrangerAlertRepository;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds the dashboard Live Alerts feed (Epic 9) from real agent output — replacing the old mock
 * feed. Composes three sources into one tiered, time-ordered list: Stranger Danger warnings
 * (Agent 4), upcoming calendar events within a week (Agent 7), and the freshest recommendations
 * (Agent 5). Read-only; computed on demand.
 */
@Service
public class AlertFeedService {

	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	private static final int CALENDAR_WINDOW_DAYS = 7;
	private static final int MAX_RECENT_RECS = 3;
	private static final int MAX_ALERTS = 8;
	private static final Map<String, Integer> TIER_RANK = Map.of("critical", 0, "warning", 1, "info", 2);

	private final StrangerAlertRepository stranger;
	private final CalendarEventRepository calendar;
	private final RecommendationRepository recommendations;

	public AlertFeedService(StrangerAlertRepository stranger, CalendarEventRepository calendar,
			RecommendationRepository recommendations) {
		this.stranger = stranger;
		this.calendar = calendar;
		this.recommendations = recommendations;
	}

	/** The current alerts, most severe + most recent first, capped to a tidy feed length. */
	public List<AlertView> live() {
		List<AlertView> out = new ArrayList<>();
		LocalDate today = LocalDate.now(TORONTO);

		for (StrangerAlert s : stranger.findAllByOrderByRiskScoreDesc()) {
			out.add(new AlertView("stranger-" + s.getTicker(), s.getRiskScore() >= 70 ? "critical" : "warning",
					"Unusual coverage: " + s.getTicker(),
					s.getCoverageCount() + " articles from " + s.getDistinctSources()
							+ " sources — possible pump-and-dump.",
					"Stranger Danger", s.getTicker(), s.getWindowStart()));
		}

		for (CalendarEvent e : calendar.findByEventDateBetweenOrderByEventDateAsc(today,
				today.plusDays(CALENDAR_WINDOW_DAYS))) {
			long days = ChronoUnit.DAYS.between(today, e.getEventDate());
			String when = days <= 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
			String prefix = e.getTicker() != null ? e.getTicker() + " · " : "";
			out.add(new AlertView("cal-" + e.getId(),
					e.getType() == CalendarEventType.EARNINGS ? "warning" : "info", e.getTitle(),
					prefix + titleCase(e.getType().name()) + " " + when, "Calendar", e.getTicker(),
					e.getEventDate().atStartOfDay(TORONTO).toInstant()));
		}

		recommendations.findTop50ByOrderByCreatedAtDesc().stream().limit(MAX_RECENT_RECS)
				.forEach(r -> out.add(new AlertView("rec-" + r.getId(), "info",
						r.getTicker() + " — " + titleCase(r.getDirection().name()),
						pct(r.getBullProbability()) + " bull · " + pct(r.getConfidence()) + " confidence",
						"Recommender", r.getTicker(), r.getCreatedAt())));

		out.sort(Comparator
				.comparingInt((AlertView a) -> TIER_RANK.getOrDefault(a.tier(), 9))
				.thenComparing(AlertView::time, Comparator.nullsLast(Comparator.reverseOrder())));
		return out.size() > MAX_ALERTS ? out.subList(0, MAX_ALERTS) : out;
	}

	private static String pct(BigDecimal zeroToOne) {
		return zeroToOne == null ? "n/a"
				: zeroToOne.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
	}

	/** {@code EX_DIVIDEND -> "Ex Dividend"}. */
	private static String titleCase(String enumName) {
		String[] parts = enumName.toLowerCase().split("_");
		StringBuilder b = new StringBuilder();
		for (String p : parts) {
			if (p.isEmpty()) {
				continue;
			}
			b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
		}
		return b.toString().strip();
	}
}
