package com.argus.briefing;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventRepository;
import com.argus.intelligence.NewsArticleRepository;
import com.argus.model.ModelGateway;
import com.argus.portfolio.HealthScoreService;
import com.argus.portfolio.LivePortfolioService;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.push.PushService;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Morning Briefing (Epic 8, FR-16). On a daily schedule (08:00 America/Toronto) it gathers the
 * facts — portfolio value/health, the overnight news count, current recommendations, and today's
 * calendar — asks the local model ({@link ModelGateway}, BIG tier) for a short narrative, persists it,
 * and pushes the headline to every device. The model call is best-effort: any failure (flaky local
 * model, unparseable output) falls back to a deterministic briefing built from the same facts, so the
 * briefing never hard-fails. A manual {@code POST /api/briefing/generate} drives the same path for
 * testing on the Mac Mini.
 */
@Service
public class BriefingService {

	private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	private static final ObjectMapper JSON = JsonMapper.builder().build();
	private static final int MAX_RECS_IN_PROMPT = 5;
	private static final int MAX_DEFERRED_IN_PROMPT = 5;

	private final LivePortfolioService livePortfolio;
	private final HealthScoreService healthScore;
	private final NewsArticleRepository news;
	private final RecommendationService recommendations;
	private final CalendarEventRepository calendar;
	private final ModelGateway gateway;
	private final PushService push;
	private final com.argus.notification.NotificationPreferencesService prefs;
	private final com.argus.notification.DeferredNotificationRepository deferred;
	private final BriefingRepository briefings;
	private final long overnightHours;

	public BriefingService(LivePortfolioService livePortfolio, HealthScoreService healthScore,
			NewsArticleRepository news, RecommendationService recommendations, CalendarEventRepository calendar,
			ModelGateway gateway, PushService push,
			com.argus.notification.NotificationPreferencesService prefs,
			com.argus.notification.DeferredNotificationRepository deferred, BriefingRepository briefings,
			@Value("${argus.briefing.overnight-hours:16}") long overnightHours) {
		this.livePortfolio = livePortfolio;
		this.healthScore = healthScore;
		this.news = news;
		this.recommendations = recommendations;
		this.calendar = calendar;
		this.gateway = gateway;
		this.push = push;
		this.prefs = prefs;
		this.deferred = deferred;
		this.briefings = briefings;
		this.overnightHours = overnightHours;
	}

	/** Daily generation + morning push. Swallows failures so a flaky model can't break the scheduler. */
	@Scheduled(cron = "${argus.briefing.cron:0 0 8 * * *}", zone = "America/Toronto")
	public void scheduledBriefing() {
		try {
			generate();
		} catch (RuntimeException ex) {
			log.warn("Scheduled morning briefing failed: {}", ex.getMessage());
		}
	}

	/**
	 * Generate, persist and push one briefing now. The model call is best-effort; on any model or parse
	 * failure a deterministic briefing from the same facts is used instead.
	 *
	 * <p>Deliberately not {@code @Transactional}: the slow local-model call must not hold a DB connection
	 * open, the fact-gathering reads each run in their own read-only transaction, and {@code save} is
	 * transactional on its own. (It is also self-invoked from {@link #scheduledBriefing()}, where a
	 * method-level transaction would be bypassed by the proxy anyway.)
	 */
	public Briefing generate() {
		Facts facts = gatherFacts();

		Parsed parsed = null;
		try {
			parsed = parse(gateway.generate(prompt(facts))); // BIG (local) tier
		} catch (RuntimeException ex) {
			log.warn("Briefing model call failed ({}) — using deterministic fallback", ex.getMessage());
		}

		String headline = parsed != null ? parsed.headline() : fallbackHeadline(facts);
		String body = parsed != null ? parsed.body() : fallbackBody(facts);

		Briefing saved = briefings.save(new Briefing(headline, body));
		try {
			markDeferredDelivered(facts); // the held NORMAL alerts have now been carried
		} catch (RuntimeException ex) {
			log.warn("Could not mark deferred items delivered: {}", ex.getMessage());
		}
		try {
			if (prefs.allow(com.argus.notification.NotificationPreferencesService.Category.BRIEFING)) {
				push.sendToAll("Your morning briefing", headline, "/");
			}
		} catch (RuntimeException ex) {
			log.warn("Briefing push failed: {}", ex.getMessage());
		}
		log.info("Morning briefing generated: {}", headline);
		return saved;
	}

	private Facts gatherFacts() {
		PortfolioSnapshot snapshot = livePortfolio.currentSnapshot();
		int health = healthScore.compute().score();
		long overnightNews = news.countByPublishedAtAfter(Instant.now().minus(Duration.ofHours(overnightHours)));
		List<Recommendation> recs = recommendations.recent();
		LocalDate today = LocalDate.now(TORONTO);
		List<CalendarEvent> events = calendar.findByEventDateBetweenOrderByEventDateAsc(today, today);
		// NORMAL-tier alerts deferred "to the next briefing" (Story 8.2 follow-up) — this IS that briefing.
		List<com.argus.notification.DeferredNotification> deferredItems = deferred
				.findByChannelAndDeliveredAtIsNullOrderByCreatedAtDesc(
						com.argus.notification.DeferredNotification.Channel.BRIEFING)
				.stream().limit(MAX_DEFERRED_IN_PROMPT).toList();
		return new Facts(snapshot, health, overnightNews, recs, events, deferredItems);
	}

	/** Mark the deferred items this briefing carried as delivered (after the briefing is saved). */
	private void markDeferredDelivered(Facts f) {
		if (f.deferredItems().isEmpty()) {
			return;
		}
		f.deferredItems().forEach(com.argus.notification.DeferredNotification::markDelivered);
		deferred.saveAll(f.deferredItems());
	}

	private String prompt(Facts f) {
		StringBuilder recs = new StringBuilder();
		f.recommendations().stream().limit(MAX_RECS_IN_PROMPT).forEach(r -> recs.append("- ")
				.append(r.getTicker()).append(": ").append(r.getDirection().name().toLowerCase())
				.append(" (").append(pct(r.getConfidence())).append(" confidence)\n"));
		if (recs.isEmpty()) {
			recs.append("- (none open)\n");
		}
		StringBuilder events = new StringBuilder();
		f.events().forEach(e -> events.append("- ").append(e.getTitle()).append('\n'));
		if (events.isEmpty()) {
			events.append("- (nothing scheduled today)\n");
		}
		StringBuilder held = new StringBuilder();
		f.deferredItems().forEach(d -> held.append("- ").append(d.getTitle()).append('\n'));
		if (held.isEmpty()) {
			held.append("- (none)\n");
		}

		return """
				You are Argus, a calm and concise personal investing assistant writing the owner's morning \
				briefing. The owner is a Canadian solo investor. Write in second person ("your portfolio"), \
				warm but factual, no hype, no financial advice disclaimers.

				TODAY'S FACTS
				Portfolio value: %s CAD (unrealized P&L %s CAD)
				Portfolio health score: %d / 100
				News articles overnight: %d
				Current recommendations:
				%s
				Calendar today:
				%s
				Lower-priority alerts held for this briefing:
				%s
				Write a brief covering: how the portfolio stands, anything notable in the overnight news volume, \
				the most relevant recommendation(s) if any, what's on the calendar today, and (only if any) the \
				held alerts in one clause. 2–4 sentences.

				Respond with ONLY a JSON object, no prose, no markdown fences:
				{"headline":"<=12 words, scannable","body":"<2-4 sentence narrative>"}
				""".formatted(money(f.snapshot().totalValueCad()), money(f.snapshot().totalPnlCad()),
				f.health(), f.overnightNews(), recs, events, held);
	}

	/** Pull {@code {"headline","body"}} out of a model response that may include prose/markdown fences. */
	private static Parsed parse(String raw) {
		if (raw == null) {
			return null;
		}
		int start = raw.indexOf('{');
		int end = raw.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return null;
		}
		try {
			JsonNode node = JSON.readTree(raw.substring(start, end + 1));
			String headline = node.path("headline").asString("").strip();
			String body = node.path("body").asString("").strip();
			if (headline.isBlank() || body.isBlank()) {
				return null;
			}
			return new Parsed(cap(headline, 160), body);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static String fallbackHeadline(Facts f) {
		return "Portfolio at %s CAD · health %d/100".formatted(money(f.snapshot().totalValueCad()), f.health());
	}

	private static String fallbackBody(Facts f) {
		String pnl = money(f.snapshot().totalPnlCad());
		String recLine = f.recommendations().isEmpty()
				? "No open recommendations need your attention."
				: f.recommendations().size() + " recommendation(s) are awaiting your decision.";
		String eventLine = f.events().isEmpty()
				? "Nothing is on your calendar today."
				: f.events().size() + " event(s) are on your calendar today.";
		String heldLine = f.deferredItems().isEmpty() ? ""
				: " " + f.deferredItems().size() + " lower-priority alert(s) were held for this briefing: "
						+ f.deferredItems().stream().map(com.argus.notification.DeferredNotification::getTitle)
								.reduce((a, b) -> a + "; " + b).orElse("") + ".";
		return "Your portfolio is worth %s CAD (unrealized P&L %s CAD) with a health score of %d/100. %d news article(s) came in overnight. %s %s%s"
				.formatted(money(f.snapshot().totalValueCad()), pnl, f.health(), f.overnightNews(), recLine,
						eventLine, heldLine);
	}

	private static String money(BigDecimal v) {
		return v == null ? "—" : String.format("%,.0f", v);
	}

	private static String pct(BigDecimal fraction) {
		if (fraction == null) {
			return "—";
		}
		return Math.round(fraction.doubleValue() * 100) + "%";
	}

	private static String cap(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max).strip();
	}

	/** The gathered inputs for one briefing. */
	private record Facts(PortfolioSnapshot snapshot, int health, long overnightNews,
			List<Recommendation> recommendations, List<CalendarEvent> events,
			List<com.argus.notification.DeferredNotification> deferredItems) {
	}

	private record Parsed(String headline, String body) {
	}
}
