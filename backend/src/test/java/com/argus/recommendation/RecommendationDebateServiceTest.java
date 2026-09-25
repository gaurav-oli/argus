package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import com.argus.model.ModelTier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The bull-vs-bear researcher debate: id validation, defensive JSON parsing, safe-empty on any
 * failure — never guesses, matches {@code CauseClassificationServiceTest}'s shape. */
class RecommendationDebateServiceTest {

	private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
	private final RecommendationDebateRepository debates = mock(RecommendationDebateRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final RecommendationDebateService service =
			new RecommendationDebateService(recommendations, debates, gateway);

	private static Recommendation recommendation() {
		ProbabilityScore score = new ProbabilityScore(0.62, 0.38, 0.55, 1.8, 1.1, List.of());
		List<AgentSignal> signals =
				List.of(new AgentSignal("agent-1-news", SignalDirection.BULLISH, 0.7, "Strong earnings coverage"));
		return new Recommendation("AAPL", score, signals, null, "3m");
	}

	@Test
	void unknownRecommendationReturnsEmptyAndNeverCallsTheModel() {
		when(recommendations.findWithSignalsById(99L)).thenReturn(Optional.empty());

		assertTrue(service.debate(99L).isEmpty());
		verify(gateway, never()).escalate(anyString());
	}

	@Test
	void parsesAWellFormedDebate() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn(
				"{\"bullCase\":\"Strong coverage supports upside.\",\"bearCase\":\"Valuation is stretched.\","
						+ "\"synthesis\":\"Evidence leans bullish.\",\"verdict\":\"BULL\"}");
		when(debates.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Optional<RecommendationDebate> result = service.debate(1L);

		assertTrue(result.isPresent());
		assertEquals(DebateVerdict.BULL, result.get().getVerdict());
		assertEquals("Strong coverage supports upside.", result.get().getBullCase());
		verify(debates).save(any());
	}

	@Test
	void toleratesCodeFencesAndSurroundingProse() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn(
				"Here's the debate:\n```json\n{\"bullCase\":\"b\",\"bearCase\":\"c\","
						+ "\"synthesis\":\"s\",\"verdict\":\"SPLIT\"}\n```\nHope that helps!");
		when(debates.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Optional<RecommendationDebate> result = service.debate(1L);

		assertTrue(result.isPresent());
		assertEquals(DebateVerdict.SPLIT, result.get().getVerdict());
	}

	@Test
	void malformedResponseIsHandledGracefully() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn("not json at all");

		assertTrue(service.debate(1L).isEmpty());
		verify(debates, never()).save(any());
	}

	@Test
	void neverPersistsAHalfFormedDebate() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn(
				"{\"bullCase\":\"b\",\"bearCase\":\"\",\"synthesis\":\"s\",\"verdict\":\"BULL\"}");

		assertTrue(service.debate(1L).isEmpty());
		verify(debates, never()).save(any());
	}

	@Test
	void unrecognizedVerdictNeverGuesses() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn(
				"{\"bullCase\":\"b\",\"bearCase\":\"c\",\"synthesis\":\"s\",\"verdict\":\"MAYBE\"}");

		assertTrue(service.debate(1L).isEmpty());
		verify(debates, never()).save(any());
	}

	@Test
	void aFailingModelCallNeverThrows() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenThrow(new RuntimeException("haiku down"));

		assertTrue(service.debate(1L).isEmpty());
	}

	@Test
	void usesEscalateNotGenerate() {
		when(recommendations.findWithSignalsById(1L)).thenReturn(Optional.of(recommendation()));
		when(gateway.escalate(anyString())).thenReturn(
				"{\"bullCase\":\"b\",\"bearCase\":\"c\",\"synthesis\":\"s\",\"verdict\":\"BULL\"}");
		when(debates.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.debate(1L);

		verify(gateway).escalate(anyString());
		verify(gateway, never()).generate(anyString(), any(ModelTier.class));
	}
}
