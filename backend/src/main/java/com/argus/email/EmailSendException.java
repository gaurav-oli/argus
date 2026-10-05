package com.argus.email;

/** Thrown when an email couldn't be sent — Gmail unconfigured, or the SMTP send itself failed. */
public class EmailSendException extends RuntimeException {

	public EmailSendException(String message) {
		super(message);
	}

	public EmailSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
