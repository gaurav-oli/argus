package com.argus.common;

/** Thrown when a requested resource does not exist; mapped to an RFC 9457 404 response. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String resource, String id) {
		super("%s '%s' not found".formatted(resource, id));
	}
}
