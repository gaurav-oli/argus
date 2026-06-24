package com.argus.conversation;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.HealthScoreService;
import com.argus.portfolio.LivePortfolioService;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.recommendation.Recommendation;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Ask AI about a single recommendation (Story 7.1, FR-30). Assembles the grounding context (the
 * recommendation's diagnostic + the current portfolio/health), composes a single prompt with the
 * prior turns, and routes it through the {@link ModelGateway} on {@link ModelTier#BIG} — the only
 * path to the model. Local-only for now; Haiku escalation is Story 7.3, personas are 7.4/7.5.
 *
 * <p>Stateless: the client passes the full conversation each turn and nothing is persisted.
 */
@Service
public class ConversationService {

	/** Defensive cap on history length so a long-running panel can't grow the prompt unbounded. */
	private static final int MAX_TURNS = 20;

	private static final String SYSTEM_FRAMING = """
			You are Argus, a personal investment assistant. Answer the user's question using ONLY the \
			context below about one stock recommendation and the user's portfolio. Cite the probabilities, \
			signals, and figures provided — never invent new numbers or probabilities. Be concise and \
			specific. This is informational analysis to help the user think, not financial advice.""";

	private final ModelGateway modelGateway;
	private final LivePortfolioService livePortfolio;
	private final HealthScoreService healthScore;
	private final RecommendationContextAssembler assembler;

	public ConversationService(ModelGateway modelGateway, LivePortfolioService livePortfolio,
			HealthScoreService healthScore, RecommendationContextAssembler assembler) {
		this.modelGateway = modelGateway;
		this.livePortfolio = livePortfolio;
		this.healthScore = healthScore;
		this.assembler = assembler;
	}

	/** Answer the latest question in {@code messages}, grounded in {@code rec} + the live portfolio. */
	public String askAboutRecommendation(Recommendation rec, List<ChatMessage> messages) {
		PortfolioSnapshot portfolio = livePortfolio.currentSnapshot();
		HealthScoreResult health = healthScore.compute();
		String grounding = assembler.assemble(rec, portfolio, health);
		String prompt = composePrompt(grounding, messages == null ? List.of() : messages);
		return modelGateway.generate(prompt, ModelTier.BIG);
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
