package com.argus.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body for PIN setup and login — a 4–6 digit numeric PIN (FR-35). */
public record PinRequest(
		@NotNull @Pattern(regexp = Pin.REGEX, message = "PIN must be 4–6 digits") String pin) {
}
