package com.argus.security;

/** Thrown when an invite email couldn't be sent — Resend unconfigured, or the API call itself failed. */
public class InviteEmailException extends RuntimeException {

	public InviteEmailException(String message) {
		super(message);
	}

	public InviteEmailException(String message, Throwable cause) {
		super(message, cause);
	}
}
