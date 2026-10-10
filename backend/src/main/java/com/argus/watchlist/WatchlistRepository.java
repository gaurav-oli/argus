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

	/** S-C1: what one person sees — their own picks plus the system's discoveries. */
	@Query("select w from WatchlistEntry w where w.userId = :userId or w.userId is null order by w.addedAt desc")
	List<WatchlistEntry> visibleTo(@Param("userId") Long userId);

	/** S-C1: one person's own pick of a ticker. */
	Optional<WatchlistEntry> findByTickerAndUserId(String ticker, Long userId);

	/** The system's (DISCOVERED) entry for a ticker, if any. */
	Optional<WatchlistEntry> findByTickerAndUserIdIsNull(String ticker);

	/** Whether anyone has picked this ticker by hand — discovery never overrides a manual pick. */
	boolean existsByTickerAndSource(String ticker, String source);

	/** Active, non-expired tickers — the universe contribution. */
	@Query("select w.ticker from WatchlistEntry w where w.active = true "
			+ "and (w.expiresAt is null or w.expiresAt > :now)")
	List<String> activeTickers(@Param("now") Instant now);

	/** S-C1: remove one person's own pick. */
	@Modifying
	@Transactional
	@Query("delete from WatchlistEntry w where w.ticker = :ticker and w.userId = :userId")
	int deleteOwn(@Param("ticker") String ticker, @Param("userId") Long userId);

	/** Remove the system's discovered entry for a ticker (admin only). */
	@Modifying
	@Transactional
	@Query("delete from WatchlistEntry w where w.ticker = :ticker and w.userId is null")
	int deleteDiscovered(@Param("ticker") String ticker);
}
