package com.argus.common;

/** Thrown when an uploaded payload exceeds the allowed size; mapped to an RFC 9457 413 response. */
public class PayloadTooLargeException extends RuntimeException {

	public PayloadTooLargeException(String message) {
		super(message);
	}
}
