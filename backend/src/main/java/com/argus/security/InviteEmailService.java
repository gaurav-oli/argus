package com.argus.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the "you're invited to Argus" email via Resend's REST API (hand-rolled HTTP call, same style
 * as {@link GoogleOAuthService} — Resend's whole API is one POST, not worth a client library for).
 * The link in the email carries the invite's own token ({@link InvitedEmail#ensureToken()}), so
 * {@code InviteTrackingController} can tell this specific person's open apart from anyone else's.
 */
@Service
public class InviteEmailService {

	private static final URI EMAILS_ENDPOINT = URI.create("https://api.resend.com/emails");

	private final ResendProperties props;
	private final String appUrl;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper json = JsonMapper.builder().build();

	public InviteEmailService(ResendProperties props, @Value("${argus.app-url:http://localhost:3000}") String appUrl) {
		this.props = props;
		// Trim any trailing slash so the built link never ends up with "//" before the query string.
		this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
	}

	/** The unique link this person's own invite email points to. Public so the admin UI can show/copy
	 * it even before (or instead of) sending an actual email. */
	public String inviteLink(String token) {
		return appUrl + "/?invite=" + token;
	}

	/** Send the invite email. Throws {@link InviteEmailException} if Resend isn't configured or the
	 * call fails — the caller (an admin-triggered action) should surface that clearly, not swallow it. */
	public void send(String toEmail, String token, String invitedByName) {
		if (!props.configured()) {
			throw new InviteEmailException("Email sending isn't set up (no Resend API key configured)");
		}
		String link = inviteLink(token);
		String payload = json.writeValueAsString(Map.of(
				"from", "Argus <" + props.fromEmail() + ">",
				"to", List.of(toEmail),
				"subject", invitedByName + " invited you to Argus",
				"html", html(link, invitedByName)));
		try {
			HttpRequest req = HttpRequest.newBuilder(EMAILS_ENDPOINT)
					.timeout(Duration.ofSeconds(15))
					.header("Authorization", "Bearer " + props.apiKey())
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
					.build();
			HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() / 100 != 2) {
				throw new InviteEmailException("Resend rejected the email (HTTP " + res.statusCode() + "): "
						+ errorMessage(res.body()));
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InviteEmailException("Sending the invite email was interrupted", ex);
		}
		catch (IOException ex) {
			throw new InviteEmailException("Could not reach Resend: " + ex.getMessage(), ex);
		}
	}

	private String errorMessage(String body) {
		try {
			JsonNode node = json.readTree(body);
			return node.path("message").asString(body);
		}
		catch (RuntimeException ex) {
			return body;
		}
	}

	/** Plain, email-client-safe HTML (inline styles, no external assets) — a simple card echoing
	 * Argus's own gold-on-dark look without relying on anything a mail client might strip. */
	private static String html(String link, String invitedByName) {
		return """
				<div style="background:#0b0f1a;padding:40px 20px;font-family:Georgia,'Times New Roman',serif;">
				  <div style="max-width:420px;margin:0 auto;background:#12161f;border:1px solid #2a2f3a;border-radius:8px;padding:36px 32px;text-align:center;">
				    <p style="margin:0 0 6px;color:#c9a24b;font-size:11px;letter-spacing:3px;text-transform:uppercase;">Private &amp; Invite-Only</p>
				    <h1 style="margin:0 0 18px;color:#f5f1e8;font-size:32px;font-weight:400;">Argus</h1>
				    <p style="margin:0 0 28px;color:#a8adb8;font-size:14px;line-height:1.6;">
				      %s has invited you to Argus — a personal investing research desk.
				      Sign in with your own Google account to get started with your own,
				      completely private portfolio.
				    </p>
				    <a href="%s" style="display:inline-block;padding:14px 32px;background:#c9a24b;color:#0b0f1a;text-decoration:none;font-size:14px;font-weight:bold;border-radius:4px;">
				      Sign in to Argus
				    </a>
				    <p style="margin:28px 0 0;color:#5a5f6b;font-size:11px;">
				      If the button doesn't work, copy this link:<br>%s
				    </p>
				  </div>
				</div>
				""".formatted(invitedByName, link, link);
	}
}
