package com.argus.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.argus.TestcontainersConfiguration;
import com.argus.common.LivePushService;
import com.argus.security.AppUserRepository;
import com.argus.security.SessionCookie;
import com.argus.security.SessionStore;
import com.argus.security.TestUserSessions;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Live STOMP-over-WebSocket checks, including S-A1: handshake requires a signed-in session, and
 * raw {@code /queue/portfolio-user\{id\}} subscribe is blocked.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class StompRoundTripIntegrationTest {

	@LocalServerPort
	int port;

	@Autowired
	LivePushService livePushService;

	@Autowired
	AppUserRepository appUsers;

	@Autowired
	SessionStore sessions;

	@Test
	void connectedClientReceivesLivePush() throws Exception {
		Cookie login = TestUserSessions.loginAsNewUser(appUsers, sessions);
		WebSocketStompClient stompClient = newClient();

		StompSession session = connect(stompClient, cookieHeaders(login));

		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		session.subscribe("/topic/demo", stringHandler(received));

		Thread.sleep(300);
		livePushService.publish("/topic/demo", "hello-live");

		String message = received.poll(5, TimeUnit.SECONDS);
		assertNotNull(message, "client should receive the published message");
		assertEquals("hello-live", message);

		session.disconnect();
		stompClient.stop();
	}

	@Test
	void handshakeWithoutSessionIsRejected() {
		WebSocketStompClient stompClient = newClient();

		ExecutionException ex = assertThrows(ExecutionException.class, () -> stompClient
				.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
				})
				.get(5, TimeUnit.SECONDS));
		assertNotNull(ex.getCause());

		stompClient.stop();
	}

	@Test
	void handshakeWithUserlessSessionIsRejected() {
		String sessionId = sessions.create("legacy-device"); // no userId
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add("Cookie", SessionCookie.NAME + "=" + sessionId);

		WebSocketStompClient stompClient = newClient();
		ExecutionException ex = assertThrows(ExecutionException.class,
				() -> stompClient.connectAsync("ws://localhost:" + port + "/ws", headers,
						new StompSessionHandlerAdapter() {
						}).get(5, TimeUnit.SECONDS));
		assertNotNull(ex.getCause());
		stompClient.stop();
	}

	@Test
	void handshakeFromDisallowedOriginIsRejected() {
		Cookie login = TestUserSessions.loginAsNewUser(appUsers, sessions);
		WebSocketStompClient stompClient = newClient();

		WebSocketHttpHeaders headers = cookieHeaders(login);
		headers.setOrigin("https://evil.example.com");

		ExecutionException ex = assertThrows(ExecutionException.class, () -> stompClient
				.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {
				})
				.get(5, TimeUnit.SECONDS));
		assertNotNull(ex.getCause());

		stompClient.stop();
	}

	@Test
	void authenticatedUserReceivesPersonalPortfolioQueue() throws Exception {
		Cookie login = TestUserSessions.loginAsNewUser(appUsers, sessions);
		Long userId = sessions.userId(login.getValue()).orElseThrow();

		WebSocketStompClient stompClient = newClient();
		StompSession session = connect(stompClient, cookieHeaders(login));

		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		session.subscribe("/user/queue/portfolio", stringHandler(received));

		Thread.sleep(300);
		livePushService.publishToUser(userId, "/queue/portfolio", "my-portfolio");

		assertEquals("my-portfolio", received.poll(5, TimeUnit.SECONDS));

		session.disconnect();
		stompClient.stop();
	}

	@Test
	void rawPortfolioUserQueueSubscribeIsRejected() throws Exception {
		Cookie victim = TestUserSessions.loginAsNewUser(appUsers, sessions);
		Long victimId = sessions.userId(victim.getValue()).orElseThrow();
		Cookie stranger = TestUserSessions.loginAsNewUser(appUsers, sessions);

		WebSocketStompClient stompClient = newClient();
		StompSession session = connect(stompClient, cookieHeaders(stranger));

		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		boolean subscribeRejectedLocally = false;
		try {
			session.subscribe("/queue/portfolio-user" + victimId, stringHandler(received));
		} catch (RuntimeException ex) {
			subscribeRejectedLocally = true;
		}

		Thread.sleep(400);
		livePushService.publishToUser(victimId, "/queue/portfolio", "secret-holdings");

		assertNull(received.poll(1, TimeUnit.SECONDS),
				"stranger must not receive another user's portfolio via raw /queue/portfolio-user{id}");
		// Guard closes the STOMP session on illegal SUBSCRIBE — either local throw or closed socket.
		assertTrue(subscribeRejectedLocally || !session.isConnected(),
				"raw /queue/portfolio-user{id} subscribe must be rejected");

		try {
			if (session.isConnected()) {
				session.disconnect();
			}
		} catch (IllegalStateException ignored) {
			// already closed by the guard — expected
		}
		stompClient.stop();
	}

	private static WebSocketStompClient newClient() {
		WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		stompClient.setMessageConverter(new StringMessageConverter());
		return stompClient;
	}

	private StompSession connect(WebSocketStompClient stompClient, WebSocketHttpHeaders headers)
			throws Exception {
		return stompClient
				.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {
				})
				.get(5, TimeUnit.SECONDS);
	}

	private static WebSocketHttpHeaders cookieHeaders(Cookie login) {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add("Cookie", login.getName() + "=" + login.getValue());
		return headers;
	}

	private static StompFrameHandler stringHandler(BlockingQueue<String> received) {
		return new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return String.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				received.add((String) payload);
			}
		};
	}
}
