package com.argus.deepanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.model.ModelGateway;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** "Explain like I'm new to investing" — rephrases an already-reached verdict, never reconsiders it. */
class DeepPlainExplanationServiceTest {

	private final DeepAnalysisRepository repo = mock(DeepAnalysisRepository.class);
	private final ModelGateway gateway = mock(ModelGateway.class);
	private final DeepPlainExplanationService service = new DeepPlainExplanationService(repo, gateway);

	private static DeepAnalysis done(DeepVerdict v) {
		DeepAnalysis d = new DeepAnalysis("AAPL", "TEST");
		d.complete(v, v == DeepVerdict.WORTH_BUYING ? 30 : null, 70, "Strong quarter", "Growing steadily", "Demand accelerating",
				"Valuation is rich", "Margins compress\nNew competitor", "Holiday season", "a close below 150",
				"", Duration.ofDays(5));
		return d;
	}

	@Test
	void generatesAndCachesAPlainExplanationOnFirstCall() {
		DeepAnalysis run = done(DeepVerdict.WORTH_BUYING);
		when(repo.findById(1L)).thenReturn(Optional.of(run));
		when(gateway.generate(anyString())).thenReturn("Argus thinks this is a buy.\n\nKEY TERMS:\nMargin — how much profit stays after costs.");

		String first = service.explain(1L).orElseThrow();

		assertEquals("Argus thinks this is a buy.\n\nKEY TERMS:\nMargin — how much profit stays after costs.", first);
		assertEquals(first, run.getPlainExplanation(), "the row itself caches the text");
	}

	@Test
	void aSecondCallReturnsTheCachedTextWithoutCallingTheModelAgain() {
		DeepAnalysis run = done(DeepVerdict.WAIT);
		when(repo.findById(2L)).thenReturn(Optional.of(run));
		when(gateway.generate(anyString())).thenReturn("First explanation.");

		service.explain(2L);
		String second = service.explain(2L).orElseThrow();

		assertEquals("First explanation.", second);
		verify(gateway, org.mockito.Mockito.times(1)).generate(anyString());
	}

	@Test
	void aModelFailureFallsBackToADeterministicExplanationRatherThanNone() {
		DeepAnalysis run = done(DeepVerdict.NOT_WORTH_BUYING);
		when(repo.findById(3L)).thenReturn(Optional.of(run));
		when(gateway.generate(anyString())).thenThrow(new RuntimeException("model unavailable"));

		String text = service.explain(3L).orElseThrow();

		assertTrue(text.contains("AAPL"));
		assertTrue(text.toLowerCase().contains("not worth buying"));
	}

	@Test
	void anUnknownAnalysisIsEmptyAndAnUnfinishedOneIsNeverExplained() {
		when(repo.findById(99L)).thenReturn(Optional.empty());
		assertTrue(service.explain(99L).isEmpty());

		DeepAnalysis queued = new DeepAnalysis("TSLA", "TEST");
		when(repo.findById(4L)).thenReturn(Optional.of(queued));
		assertTrue(service.explain(4L).isEmpty(), "no verdict yet means nothing to explain");
		verify(gateway, never()).generate(anyString());
	}
}
