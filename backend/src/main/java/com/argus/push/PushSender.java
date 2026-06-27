package com.argus.push;

/**
 * Transport for a single encrypted Web Push message to one subscription (Epic 8, FR-17). Isolated
 * behind this interface so the VAPID encryption/HTTP library is swappable and never leaks into the
 * service layer. Implementations must not throw — they classify the outcome as {@link Result}.
 */
public interface PushSender {

	/** Encrypt {@code payloadJson} and deliver it to {@code subscription}. */
	Result send(PushSubscription subscription, String payloadJson);

	enum Result {
		/** Delivered (2xx). */
		SENT,
		/** The subscription is gone (404/410) — the caller should prune it. */
		EXPIRED,
		/** Transient or unexpected failure — keep the subscription, try again next time. */
		FAILED
	}
}
