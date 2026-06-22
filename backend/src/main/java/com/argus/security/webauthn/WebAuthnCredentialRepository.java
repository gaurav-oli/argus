package com.argus.security.webauthn;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for registered passkeys. The credential id (bytea) is the primary key. */
public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, byte[]> {

	List<WebAuthnCredential> findByUserHandle(byte[] userHandle);
}
