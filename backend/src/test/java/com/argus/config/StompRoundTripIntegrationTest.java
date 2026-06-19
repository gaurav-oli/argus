package com.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.argus.TestcontainersConfiguration;
import com.argus.common.LivePushService;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Live round-trip (AC #3): a real STOMP-over-WebSocket client connects, subscribes to
 * {@code /topic/demo}, and receives a message published via {@link LivePushService}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class StompRoundTripIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	LivePushService livePushService;

	@Test
	void connectedClientReceivesLivePush() throws Exception {
		WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		stompClient.setMessageConverter(new StringMessageConverter());

		StompSession session = stompClient
				.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
				})
				.get(5, TimeUnit.SECONDS);

		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		session.subscribe("/topic/demo", new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return String.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				received.add((String) payload);
			}
		});

		// Give the SUBSCRIBE frame time to register on the broker before publishing.
		Thread.sleep(300);
		livePushService.publish("/topic/demo", "hello-live");

		String message = received.poll(5, TimeUnit.SECONDS);
		assertNotNull(message, "client should receive the published message");
		assertEquals("hello-live", message);

		session.disconnect();
		stompClient.stop();
	}
}
