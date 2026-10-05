package com.argus.security;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitedEmailRepository extends JpaRepository<InvitedEmail, String> {

	List<InvitedEmail> findAllByOrderByInvitedAtAsc();

	/** Look up by this person's own invite-link token (open-tracking). */
	Optional<InvitedEmail> findByToken(String token);
}
