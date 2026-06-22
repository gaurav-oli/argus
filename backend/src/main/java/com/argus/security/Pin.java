package com.argus.security;

import java.util.regex.Pattern;

/**
 * PIN format rule shared by setup and login: a 4–6 digit numeric string (FR-35).
 * Centralized so the controller-layer {@code @Pattern} and any programmatic checks
 * never drift apart.
 */
public final class Pin {

	/** 4–6 digits, nothing else. */
	public static final String REGEX = "^\\d{4,6}$";

	private static final Pattern PATTERN = Pattern.compile(REGEX);

	private Pin() {
	}

	public static boolean isValid(String raw) {
		return raw != null && PATTERN.matcher(raw).matches();
	}
}
