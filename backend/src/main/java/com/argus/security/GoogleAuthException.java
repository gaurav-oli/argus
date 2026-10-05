package com.argus.security;

/** Anything that goes wrong talking to Google or verifying its ID token. Caught at the callback
 * and turned into a plain "sign-in failed, try again" redirect — never a stack trace in the browser. */
public class GoogleAuthException extends RuntimeException {

	public GoogleAuthException(String message, Throwable cause) {
		super(message, cause);
	}

	public GoogleAuthException(String message) {
		super(message);
	}
}
