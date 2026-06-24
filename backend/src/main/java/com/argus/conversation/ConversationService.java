package com.argus.conversation;

import com.argus.calendar.CalendarEvent;
import com.argus.calendar.CalendarEventRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.HealthScoreService;
import com.argus.portfolio.LivePortfolioService;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Ask AI about a recommendation (Story 7.1, FR-30) or the whole portfolio (Story 7.2, FR-31).
 * Assembles the grounding context, composes a single prompt with the prior turns, and routes it
 * through the {@link ModelGateway} on {@link ModelTier#BIG} — the only path to the model. Local-only
 * for now; Haiku escalation is Story 7.3, personas are 7.4/7.5.
 *
 * <p>Stateless: the client passes the full conversation each turn and nothing is persisted.
 */
@Service
public class ConversationService {

	/** Defensive cap on history length so a long-running panel can't grow the prompt unbounded. */
	private static final int MAX_TURNS = 20;
	/** Bounds on the portfolio-chat grounding so the larger context stays within model limits + latency. */
	private static final int CALENDAR_WINDOW_DAYS = 14;
	private static final int MAX_RECENT_RECS = 10;
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	/** Until a persisted investor profile exists (Story 7.5 + a profile entity), ground from a constant. */
	private static final String INVESTOR_PROFILE =
			"Canadian solo investor; home currency CAD; holds US- and Canada-listed equities; "
					+ "TFSA/RRSP tax context matters (US dividend withholding, contribution room).";

	private static final String SYSTEM_FRAMING = """
			You are Argus, a personal investment assistant. Answer the user's question using ONLY the \
			context below about the user's portfolio and the recommendation(s) shown. Cite the \
			probabilities, signals, and figures provided — never invent new numbers or probabilities. \
			Be concise and specific. This is informational analysis to help the user think, not \
			financial advice.""";

	private final ModelGateway modelGateway;
	private final LivePortfolioService livePortfolio;
	private final HealthScoreService healthScore;
	private final RecommendationContextAssembler recommendationAssembler;
	private final PortfolioContextAssembler portfolioAssembler;
	private final RecommendationService recommendations;
	private final CalendarEventRepository calendarEvents;

	public ConversationService(ModelGateway modelGateway, LivePortfolioService livePortfolio,
			HealthScoreService healthScore, RecommendationContextAssembler recommendationAssembler,
			PortfolioContextAssembler portfolioAssembler, RecommendationService recommendations,
			CalendarEventRepository calendarEvents) {
		this.modelGateway = modelGateway;
		this.livePortfolio = livePortfolio;
		this.healthScore = healthScore;
		this.recommendationAssembler = recommendationAssembler;
		this.portfolioAssembler = portfolioAssembler;
		this.recommendations = recommendations;
		this.calendarEvents = calendarEvents;
	}

	/** Answer the latest question in {@code messages}, grounded in {@code rec} + the live portfolio. */
	public String askAboutRecommendation(Recommendation rec, List<ChatMessage> messages) {
		PortfolioSnapshot portfolio = livePortfolio.currentSnapshot();
		HealthScoreResult health = healthScore.compute();
		String grounding = recommendationAssembler.assemble(rec, portfolio, health);
		return modelGateway.generate(composePrompt(grounding, safe(messages)), ModelTier.BIG);
	}

	/** Answer the latest question, grounded in holdings + health + upcoming calendar + recent recs. */
	public String askAboutPortfolio(List<ChatMessage> messages) {
		PortfolioSnapshot portfolio = livePortfolio.currentSnapshot();
		HealthScoreResult health = healthScore.compute();
		LocalDate today = LocalDate.now(TORONTO);
		List<CalendarEvent> events = calendarEvents
				.findByEventDateBetweenOrderByEventDateAsc(today, today.plusDays(CALENDAR_WINDOW_DAYS));
		List<Recommendation> recentRecs = recommendations.recent().stream().limit(MAX_RECENT_RECS).toList();
		String grounding = portfolioAssembler.assemble(portfolio, health, events, recentRecs,
				INVESTOR_PROFILE, today);
		return modelGateway.generate(composePrompt(grounding, safe(messages)), ModelTier.BIG);
	}

	private static List<ChatMessage> safe(List<ChatMessage> messages) {
		return messages == null ? List.of() : messages;
	}

	private static String composePrompt(String grounding, List<ChatMessage> messages) {
		List<ChatMessage> recent = messages.size() > MAX_TURNS
				? messages.subList(messages.size() - MAX_TURNS, messages.size())
				: messages;

		StringBuilder b = new StringBuilder();
		b.append(SYSTEM_FRAMING).append("\n\n").append(grounding).append("\n=== CONVERSATION ===\n");
		for (ChatMessage m : recent) {
			String speaker = "assistant".equalsIgnoreCase(m.role()) ? "Assistant" : "User";
			b.append(speaker).append(": ").append(m.content() == null ? "" : m.content().strip()).append('\n');
		}
		b.append("Assistant:");
		return b.toString();
	}
}
