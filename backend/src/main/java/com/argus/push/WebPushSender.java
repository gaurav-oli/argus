package com.argus.push;

import java.security.Security;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PushSender} backed by the {@code nl.martijndwars:web-push} library — it performs the RFC 8291
 * payload encryption and RFC 8292 VAPID signing and POSTs to the push service. The library's
 * {@code PushService} is built lazily from the VAPID keys (and reused), and BouncyCastle is registered
 * once on construction. All failures are caught and classified — this never throws.
 *
 * <p>NOTE (Mac Mini validation): this path requires a real VAPID private key and network egress to the
 * browser push services, neither of which can be exercised on the dev MacBook. See
 * {@code docs/mac-mini-validation.md}.
 */
public class WebPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

	private final PushProperties props;
	private nl.martijndwars.webpush.PushService delegate;

	public WebPushSender(PushProperties props) {
		this.props = props;
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	@Override
	public Result send(PushSubscription subscription, String payloadJson) {
		try {
			Subscription target = new Subscription(subscription.getEndpoint(),
					new Subscription.Keys(subscription.getP256dh(), subscription.getAuth()));
			var response = delegate().send(new Notification(target, payloadJson));
			int status = response.getStatusLine().getStatusCode();
			if (status == 404 || status == 410) {
				return Result.EXPIRED;
			}
			if (status >= 200 && status < 300) {
				return Result.SENT;
			}
			log.warn("Web push to {} returned HTTP {}", host(subscription), status);
			return Result.FAILED;
		} catch (Exception ex) {
			log.warn("Web push to {} failed: {}", host(subscription), ex.getMessage());
			return Result.FAILED;
		}
	}

	private synchronized nl.martijndwars.webpush.PushService delegate() throws Exception {
		if (delegate == null) {
			delegate = new nl.martijndwars.webpush.PushService(
					props.publicKey(), props.privateKey(), props.subject());
		}
		return delegate;
	}

	/** Just the push-service host, for logs — endpoints carry an opaque per-device token we don't log. */
	private static String host(PushSubscription sub) {
		try {
			return java.net.URI.create(sub.getEndpoint()).getHost();
		} catch (RuntimeException ex) {
			return "<unknown>";
		}
	}
}
