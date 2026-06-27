package com.argus.push;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push registry + broadcaster (Epic 8, FR-17). Stores one {@link PushSubscription} per device and
 * fans a notification out to all of them, pruning subscriptions the push service reports as gone. The
 * payload shape ({@code {title, body, url}}) is what {@code public/sw.js} reads in its {@code push}
 * handler. With no VAPID keys configured, {@link #sendToAll} is a no-op so the rest of Argus is
 * unaffected.
 */
@Service
public class PushService {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private final PushSubscriptionRepository subscriptions;
	private final PushProperties props;
	private final PushSender sender;

	public PushService(PushSubscriptionRepository subscriptions, PushProperties props, PushSender sender) {
		this.subscriptions = subscriptions;
		this.props = props;
		this.sender = sender;
	}

	/** The VAPID public key the browser needs to subscribe (base64url). Empty when push is unconfigured. */
	public String publicKey() {
		return props.publicKey();
	}

	public boolean isConfigured() {
		return props.isConfigured();
	}

	/** Register (or refresh, if the endpoint already exists) a device's subscription. */
	@Transactional
	public void subscribe(String endpoint, String p256dh, String auth) {
		subscriptions.findByEndpoint(endpoint).ifPresentOrElse(existing -> {
			existing.refresh(p256dh, auth);
			subscriptions.save(existing);
		}, () -> subscriptions.save(new PushSubscription(endpoint, p256dh, auth)));
	}

	/** Drop a device's subscription (e.g. the browser revoked permission). */
	@Transactional
	public void unsubscribe(String endpoint) {
		subscriptions.deleteByEndpoint(endpoint);
	}

	/**
	 * Send a notification to every registered device. Returns the number delivered; expired
	 * subscriptions are pruned. No-op (returns 0) when VAPID keys are not configured.
	 *
	 * @param title heading shown in the OS notification
	 * @param body  one-line message
	 * @param url   in-app path opened when the notification is clicked (e.g. {@code "/"})
	 */
	@Transactional
	public int sendToAll(String title, String body, String url) {
		return sendToAll(title, body, url, false);
	}

	/**
	 * As {@link #sendToAll(String, String, String)}, but {@code requireInteraction} keeps the OS
	 * notification on screen until the user acts on it — used for CRITICAL alerts (Story 8.2).
	 */
	@Transactional
	public int sendToAll(String title, String body, String url, boolean requireInteraction) {
		if (!props.isConfigured()) {
			log.debug("Web push not configured (no VAPID keys) — skipping notification '{}'", title);
			return 0;
		}
		String payload = payload(title, body, url, requireInteraction);
		int sent = 0;
		for (PushSubscription sub : subscriptions.findAll()) {
			switch (sender.send(sub, payload)) {
				case SENT -> sent++;
				case EXPIRED -> subscriptions.delete(sub);
				case FAILED -> { /* keep it; retry on the next notification */ }
			}
		}
		if (sent > 0) {
			log.info("Web push '{}' delivered to {} device(s)", title, sent);
		}
		return sent;
	}

	private static String payload(String title, String body, String url, boolean requireInteraction) {
		return JSON.writeValueAsString(Map.of(
				"title", title == null || title.isBlank() ? "Argus" : title,
				"body", body == null ? "" : body,
				"url", url == null || url.isBlank() ? "/" : url,
				"requireInteraction", requireInteraction));
	}
}
