package com.argus.conversation;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the single {@link InvestorProfile} row (Story 7.6). */
public interface InvestorProfileRepository extends JpaRepository<InvestorProfile, Short> {

	default Optional<InvestorProfile> findSingleton() {
		return findById(InvestorProfile.SINGLETON_ID);
	}
}
