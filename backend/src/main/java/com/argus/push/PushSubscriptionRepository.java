package com.argus.push;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link PushSubscription} rows (Epic 8, FR-17). */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

	Optional<PushSubscription> findByEndpoint(String endpoint);

	void deleteByEndpoint(String endpoint);

	/** Ownership-scoped delete (S-A6 / M4) — never remove another user's subscription by endpoint. */
	void deleteByEndpointAndUserId(String endpoint, Long userId);

	/** This person's own registered device(s) — for a personal push (e.g. their own Briefing). */
	List<PushSubscription> findByUserId(Long userId);
}
