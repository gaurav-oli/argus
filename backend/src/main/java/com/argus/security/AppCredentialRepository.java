package com.argus.security;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the single {@link AppCredential} row. Use {@link #findSingleton()} /
 * {@link #existsSingleton()} rather than assuming the id, so callers don't hard-code it.
 */
public interface AppCredentialRepository extends JpaRepository<AppCredential, Short> {

	default Optional<AppCredential> findSingleton() {
		return findById(AppCredential.SINGLETON_ID);
	}

	default boolean existsSingleton() {
		return existsById(AppCredential.SINGLETON_ID);
	}
}
