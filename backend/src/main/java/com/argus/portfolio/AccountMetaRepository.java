package com.argus.portfolio;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link AccountMeta} rows (per-account owner identity). */
public interface AccountMetaRepository extends JpaRepository<AccountMeta, Long> {

	Optional<AccountMeta> findByInstitutionAndAccount(String institution, String account);

	/** Owner rows for an account label, ignoring institution (cash balances carry no institution). */
	List<AccountMeta> findByAccount(String account);

	List<AccountMeta> findAll();
}
