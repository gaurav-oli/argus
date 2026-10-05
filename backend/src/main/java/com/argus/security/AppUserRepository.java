package com.argus.security;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByGoogleSub(String googleSub);

	Optional<AppUser> findByEmailIgnoreCase(String email);

	/** Everyone, oldest account first — the admin's user-stats view. */
	List<AppUser> findAllByOrderByCreatedAtAsc();
}
