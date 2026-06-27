package com.argus.portfolio;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link CashBalance} rows. */
public interface CashBalanceRepository extends JpaRepository<CashBalance, Long> {

	List<CashBalance> findAllByOrderByAccountAsc();

	Optional<CashBalance> findByAccountAndCurrency(String account, String currency);
}
