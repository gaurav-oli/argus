package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** PIN format rule: 4–6 digits only (FR-35). */
class PinTest {

	@ParameterizedTest
	@ValueSource(strings = {"1234", "12345", "123456"})
	void acceptsFourToSixDigits(String pin) {
		assertTrue(Pin.isValid(pin));
	}

	@ParameterizedTest
	@ValueSource(strings = {"123", "1234567", "12a4", "abcd", " 1234", "12 34", ""})
	void rejectsNonConforming(String pin) {
		assertFalse(Pin.isValid(pin));
	}

	@Test
	void rejectsNull() {
		assertFalse(Pin.isValid(null));
	}
}
