package com.argus.common;

/** The caller is authenticated but not allowed to do this — e.g. an admin-only endpoint, or an
 * email that isn't on the invite list trying to sign in. Maps to 403 (not 401: identity is known). */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}
}
