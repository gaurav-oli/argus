package com.argus.security.webauthn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yubico.webauthn.data.ByteArray;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The single-user identity mapping (no DB needed for these methods). */
class ArgusCredentialRepositoryTest {

	private final ArgusCredentialRepository repo = new ArgusCredentialRepository(null);

	@Test
	void userHandleForKnownUsername() {
		assertEquals(Optional.of(ArgusCredentialRepository.USER_HANDLE),
				repo.getUserHandleForUsername("argus"));
	}

	@Test
	void noUserHandleForUnknownUsername() {
		assertTrue(repo.getUserHandleForUsername("someone-else").isEmpty());
	}

	@Test
	void usernameForKnownHandle() {
		assertEquals(Optional.of("argus"),
				repo.getUsernameForUserHandle(ArgusCredentialRepository.USER_HANDLE));
	}

	@Test
	void noUsernameForUnknownHandle() {
		assertFalse(repo.getUsernameForUserHandle(new ByteArray(new byte[] {1, 2, 3})).isPresent());
	}
}
