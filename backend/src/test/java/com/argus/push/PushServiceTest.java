package com.argus.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

/** S-A6 / M4: unsubscribe is ownership-scoped. */
class PushServiceTest {

	private final PushSubscriptionRepository subscriptions = mock(PushSubscriptionRepository.class);
	private final PushProperties props = mock(PushProperties.class);
	private final PushSender sender = mock(PushSender.class);
	private final PushService push = new PushService(subscriptions, props, sender);

	@Test
	void unsubscribeDeletesOnlyCallerOwnedEndpoint() {
		push.unsubscribe("https://push.example/ep", 42L);
		verify(subscriptions).deleteByEndpointAndUserId("https://push.example/ep", 42L);
		verify(subscriptions, never()).deleteByEndpoint("https://push.example/ep");
	}

	@Test
	void unsubscribeNoopsWithoutUserId() {
		push.unsubscribe("https://push.example/ep", null);
		verifyNoInteractions(subscriptions);
	}
}
