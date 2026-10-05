package com.argus.security;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends the "you're invited to Argus" email via the admin's own Gmail account (SMTP + an App
 * Password — no domain to own or verify). Resend's unverified sandbox sender was tried first but
 * turned out to only deliver to the Resend account's own address, useless for inviting anyone else;
 * a real Gmail account sending real mail has no such restriction.
 *
 * <p>The link in the email carries the invite's own token ({@link InvitedEmail#ensureToken()}), so
 * {@code InviteTrackingController} can tell this specific person's open apart from anyone else's.
 */
@Service
public class InviteEmailService {

	private final JavaMailSender mailSender;
	private final String fromAddress;
	private final String appUrl;

	public InviteEmailService(JavaMailSender mailSender, @Value("${spring.mail.username:}") String fromAddress,
			@Value("${argus.app-url:http://localhost:3000}") String appUrl) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
		// Trim any trailing slash so the built link never ends up with "//" before the query string.
		this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
	}

	public boolean configured() {
		return !fromAddress.isBlank();
	}

	/** The unique link this person's own invite email points to. Public so the admin UI can show/copy
	 * it even before (or instead of) sending an actual email. */
	public String inviteLink(String token) {
		return appUrl + "/?invite=" + token;
	}

	/** Send the invite email. Throws {@link InviteEmailException} if Gmail isn't configured or the
	 * send itself fails — the caller (an admin-triggered action) should surface that clearly, not
	 * swallow it. */
	public void send(String toEmail, String token, String invitedByName) {
		if (!configured()) {
			throw new InviteEmailException("Email sending isn't set up (no Gmail address/app password configured)");
		}
		String link = inviteLink(token);
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
			helper.setFrom(fromAddress, "Argus");
			helper.setTo(toEmail);
			helper.setSubject(invitedByName + " invited you to Argus");
			helper.setText(html(link, invitedByName), true);
			mailSender.send(message);
		}
		catch (MailException ex) {
			throw new InviteEmailException("Gmail rejected the email: " + ex.getMostSpecificCause().getMessage(), ex);
		}
		catch (MessagingException | java.io.UnsupportedEncodingException ex) {
			throw new InviteEmailException("Could not build the invite email: " + ex.getMessage(), ex);
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
