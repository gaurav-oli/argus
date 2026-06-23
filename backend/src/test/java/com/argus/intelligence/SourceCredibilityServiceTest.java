package com.argus.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.agent.AgentEventPublisher;
import com.argus.notification.NotificationStream;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Engine behavior: register-at-35, persistence, and notify-once on auto-block (Story 4.3). */
class SourceCredibilityServiceTest {

	private final SourceCredibilityRepository repo = mock(SourceCredibilityRepository.class);
	private final AgentEventPublisher events = mock(AgentEventPublisher.class);
	private final SourceCredibilityService service = new SourceCredibilityService(repo, events);

	@Test
	void registerCreatesUnknownSourceAtBaseline() {
		when(repo.findBySource("acme")).thenReturn(Optional.empty());
		when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.register("acme");

		ArgumentCaptor<SourceCredibility> saved = ArgumentCaptor.forClass(SourceCredibility.class);
		verify(repo).save(saved.capture());
		assertEquals(35, saved.getValue().getScore());
		assertEquals(CredibilityTier.BRONZE, saved.getValue().getTier());
	}

	@Test
	void registerReturnsExistingWithoutOverwriting() {
		SourceCredibility existing = SourceCredibility.unknown("acme");
		existing.recordOutcome(true); // 37
		when(repo.findBySource("acme")).thenReturn(Optional.of(existing));

		SourceCredibility result = service.register("acme");

		assertEquals(37, result.getScore());
	}

	@Test
	void autoBlockNotifiesExactlyOnceOnTransition() {
		SourceCredibility c = SourceCredibility.unknown("spam.news"); // 35
		when(repo.findBySource("spam.news")).thenReturn(Optional.of(c));
		when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		// 35 → below 10 takes nine −3 steps; keep going past the crossing.
		for (int i = 0; i < 12; i++) {
			service.recordOutcome("spam.news", false);
		}

		assertTrue(c.isBlocked());
		verify(events, times(1)).publish(eq(NotificationStream.KEY),
				eq("source.auto_blocked"), any());
	}

	@Test
	void correctOutcomeNeverNotifies() {
		SourceCredibility c = SourceCredibility.unknown("good.news");
		when(repo.findBySource("good.news")).thenReturn(Optional.of(c));
		when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.recordOutcome("good.news", true);

		verify(events, times(0)).publish(anyString(), anyString(), any());
	}

	@Test
	void isBlockedReflectsStoredState() {
		SourceCredibility blocked = SourceCredibility.unknown("x");
		for (int i = 0; i < 9; i++) {
			blocked.recordOutcome(false);
		}
		when(repo.findBySource("x")).thenReturn(Optional.of(blocked));
		when(repo.findBySource("y")).thenReturn(Optional.empty());

		assertTrue(service.isBlocked("x"));
		assertFalse(service.isBlocked("y")); // unknown source is not blocked
	}
}
