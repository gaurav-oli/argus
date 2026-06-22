package com.argus.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Argon2id hashing: hash hides the PIN; matches verifies correctly. */
class PinHasherTest {

	private final PinHasher hasher = new PinHasher();

	@Test
	void hashIsNotThePlaintext() {
		String hash = hasher.hash("1234");
		assertNotEquals("1234", hash);
		assertTrue(hash.startsWith("$argon2"), "should be an Argon2 encoded hash");
	}

	@Test
	void matchesCorrectPin() {
		String hash = hasher.hash("123456");
		assertTrue(hasher.matches("123456", hash));
	}

	@Test
	void rejectsWrongPin() {
		String hash = hasher.hash("123456");
		assertFalse(hasher.matches("654321", hash));
	}

	@Test
	void differentHashesForSamePin() {
		// Argon2 salts each hash, so two encodings of the same PIN differ but both verify.
		String a = hasher.hash("1234");
		String b = hasher.hash("1234");
		assertNotEquals(a, b);
		assertTrue(hasher.matches("1234", a));
		assertTrue(hasher.matches("1234", b));
	}
}
