package com.argus.security;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitedEmailRepository extends JpaRepository<InvitedEmail, String> {

	List<InvitedEmail> findAllByOrderByInvitedAtAsc();
}
