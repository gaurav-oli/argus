package com.argus.common;

/** Thrown when a request conflicts with current state (e.g. PIN already set); RFC 9457 409. */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}
}
