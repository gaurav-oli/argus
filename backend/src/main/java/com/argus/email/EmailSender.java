package com.argus.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * The one place Argus sends real email from — over the admin's own Gmail account via SMTP (an App
 * Password, no domain to own or verify). Shared by every feature that needs to email someone (the
 * admin-sent friend invite, a "your portfolio was updated" confirmation, …) rather than each one
 * reaching for {@link JavaMailSender} directly.
 */
@Service
public class EmailSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public EmailSender(JavaMailSender mailSender, @Value("${spring.mail.username:}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	public boolean configured() {
		return !fromAddress.isBlank();
	}

	/** Send an HTML email. Throws {@link EmailSendException} if nothing is configured or the send
	 * itself fails — callers of a user-triggered action should surface that clearly, not swallow it. */
	public void send(String toEmail, String subject, String html) {
		if (!configured()) {
			throw new EmailSendException("Email sending isn't set up (no Gmail address/app password configured)");
		}
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
			helper.setFrom(fromAddress, "Argus");
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(html, true);
			mailSender.send(message);
		}
		catch (MailException ex) {
			throw new EmailSendException("Gmail rejected the email: " + ex.getMostSpecificCause().getMessage(), ex);
		}
		catch (MessagingException | UnsupportedEncodingException ex) {
			throw new EmailSendException("Could not build the email: " + ex.getMessage(), ex);
		}
	}
}
