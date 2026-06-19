package com.argus.model;

/** Raised when the Model Gateway cannot produce a response (and no fallback succeeded). */
public class ModelGatewayException extends RuntimeException {

	public ModelGatewayException(String message, Throwable cause) {
		super(message, cause);
	}
}
