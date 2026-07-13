package com.argus.watchlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry, Long> {

	List<WatchlistEntry> findAllByOrderByAddedAtDesc();

	Optional<WatchlistEntry> findByTicker(String ticker);

	boolean existsByTicker(String ticker);

	/** Active, non-expired tickers — the universe contribution. */
	@Query("select w.ticker from WatchlistEntry w where w.active = true "
			+ "and (w.expiresAt is null or w.expiresAt > :now)")
	List<String> activeTickers(@Param("now") Instant now);

	@Modifying
	@Transactional
	@Query("delete from WatchlistEntry w where w.ticker = :ticker")
	int deleteByTicker(@Param("ticker") String ticker);
}
