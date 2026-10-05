package com.argus.conversation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for each person's {@link InvestorProfile} row, keyed by their own user id. */
public interface InvestorProfileRepository extends JpaRepository<InvestorProfile, Long> {
}
