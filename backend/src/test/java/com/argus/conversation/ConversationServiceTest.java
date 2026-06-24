package com.argus.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.calendar.CalendarEventRepository;
import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import com.argus.portfolio.HealthScoreResult;
import com.argus.portfolio.HealthScoreService;
import com.argus.portfolio.LivePortfolioService;
import com.argus.portfolio.PortfolioSnapshot;
import com.argus.recommendation.AgentSignal;
import com.argus.recommendation.ProbabilityScore;
import com.argus.recommendation.Recommendation;
import com.argus.recommendation.RecommendationService;
import com.argus.recommendation.SignalDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The service grounds the prompt, routes to the BIG tier, and returns the gateway's answer (Stories 7.1/7.2). */
class ConversationServiceTest {

	private final ModelGateway gateway = mock(ModelGateway.class);
	private final LivePortfolioService livePortfolio = mock(LivePortfolioService.class);
	private final HealthScoreService healthScore = mock(HealthScoreService.class);
	private final RecommendationService recommendations = mock(RecommendationService.class);
	private final CalendarEventRepository calendarEvents = mock(CalendarEventRepository.class);
	private final ConversationService service = new ConversationService(gateway, livePortfolio, healthScore,
			new RecommendationContextAssembler(), new PortfolioContextAssembler(), recommendations, calendarEvents);

	private static Recommendation recommendation() {
		ProbabilityScore score = new ProbabilityScore(0.70, 0.30, 0.65, 4.0, 1.0, List.of());
		return new Recommendation("AAPL", score,
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 3, "Strong sentiment")),
				null, "3 months");
	}

	private static PortfolioSnapshot emptySnapshot() {
		return new PortfolioSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, Instant.now(),
				List.of());
	}

	@Test
	void recommendationChatRoutesToBigTierAndReturnsTheAnswer() {
		when(livePortfolio.currentSnapshot()).thenReturn(emptySnapshot());
		when(healthScore.compute()).thenReturn(new HealthScoreResult(100, List.of(), Instant.now()));
		when(gateway.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn("The bullish call rests on strong news sentiment.");

		String answer = service.askAboutRecommendation(recommendation(),
				List.of(new ChatMessage("user", "Why is this bullish?")));

		assertEquals("The bullish call rests on strong news sentiment.", answer);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(gateway).generate(prompt.capture(), eq(ModelTier.BIG));
		String sent = prompt.getValue();
		assertTrue(sent.contains("Why is this bullish?"), sent);
		assertTrue(sent.contains("agent-1-news"), sent);
		assertTrue(sent.contains("Strong sentiment"), sent);
		assertTrue(sent.strip().endsWith("Assistant:"), sent);
	}

	@Test
	void recommendationChatIncludesPriorTurnsForFollowUps() {
		when(livePortfolio.currentSnapshot()).thenReturn(emptySnapshot());
		when(healthScore.compute()).thenReturn(new HealthScoreResult(100, List.of(), Instant.now()));
		when(gateway.generate(anyString(), eq(ModelTier.BIG))).thenReturn("ok");

		service.askAboutRecommendation(recommendation(), List.of(
				new ChatMessage("user", "Is this risky?"),
				new ChatMessage("assistant", "Earnings are near."),
				new ChatMessage("user", "How near?")));

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(gateway).generate(prompt.capture(), eq(ModelTier.BIG));
		String sent = prompt.getValue();
		assertTrue(sent.contains("Is this risky?"), sent);
		assertTrue(sent.contains("Earnings are near."), sent);
		assertTrue(sent.contains("How near?"), sent);
	}

	@Test
	void portfolioChatGroundsOnPortfolioAndRoutesToBigTier() {
		when(livePortfolio.currentSnapshot()).thenReturn(emptySnapshot());
		when(healthScore.compute()).thenReturn(new HealthScoreResult(100, List.of(), Instant.now()));
		when(calendarEvents.findByEventDateBetweenOrderByEventDateAsc(any(), any())).thenReturn(List.of());
		when(recommendations.recent()).thenReturn(List.of(recommendation()));
		when(gateway.generate(anyString(), eq(ModelTier.BIG)))
				.thenReturn("Your portfolio looks concentrated.");

		String answer = service.askAboutPortfolio(List.of(new ChatMessage("user", "How's my portfolio?")));

		assertEquals("Your portfolio looks concentrated.", answer);

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(gateway).generate(prompt.capture(), eq(ModelTier.BIG));
		String sent = prompt.getValue();
		assertTrue(sent.contains("How's my portfolio?"), sent);
		assertTrue(sent.contains("INVESTOR PROFILE"), sent);
		assertTrue(sent.contains("RECENT RECOMMENDATIONS"), sent);
		assertTrue(sent.contains("AAPL"), sent);
		assertTrue(sent.strip().endsWith("Assistant:"), sent);
	}
}
