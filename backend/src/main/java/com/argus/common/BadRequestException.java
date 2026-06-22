package com.argus.common;

/** Thrown for a malformed or unprocessable request; mapped to an RFC 9457 400 response. */
public class BadRequestException extends RuntimeException {

	public BadRequestException(String message) {
		super(message);
	}
}
