package com.argus.push;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Web Push subscription endpoints (Epic 8, FR-17), session-gated under {@code /api/push}. The browser
 * fetches the VAPID public key, calls {@code PushManager.subscribe(...)}, then POSTs the resulting
 * subscription here for storage. Subscription JSON matches the browser's
 * {@code PushSubscription.toJSON()} ({@code { endpoint, keys: { p256dh, auth } }}).
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

	private final PushService push;

	public PushController(PushService push) {
		this.push = push;
	}

	/** The VAPID public key the frontend passes as {@code applicationServerKey}. */
	@GetMapping("/key")
	public KeyResponse key() {
		return new KeyResponse(push.publicKey());
	}

	@PostMapping("/subscribe")
	public void subscribe(@RequestBody SubscriptionBody body) {
		if (body == null || body.endpoint() == null || body.endpoint().isBlank()
				|| body.keys() == null || body.keys().p256dh() == null || body.keys().auth() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint and keys are required");
		}
		push.subscribe(body.endpoint(), body.keys().p256dh(), body.keys().auth());
	}

	@PostMapping("/unsubscribe")
	public void unsubscribe(@RequestBody UnsubscribeBody body) {
		if (body == null || body.endpoint() == null || body.endpoint().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint is required");
		}
		push.unsubscribe(body.endpoint());
	}

	/** Empty {@code publicKey} signals push is unconfigured server-side, so the UI can explain why. */
	public record KeyResponse(String publicKey) {
	}

	public record SubscriptionBody(String endpoint, Keys keys) {
		public record Keys(String p256dh, String auth) {
		}
	}

	public record UnsubscribeBody(String endpoint) {
	}
}
