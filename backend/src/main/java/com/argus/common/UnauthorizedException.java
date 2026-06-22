package com.argus.common;

/** Thrown when a request lacks valid authentication; mapped to an RFC 9457 401 response. */
public class UnauthorizedException extends RuntimeException {

	public UnauthorizedException(String message) {
		super(message);
	}
}
