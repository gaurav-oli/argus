package com.argus.fundamentals;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FundamentalsSnapshotRepository extends JpaRepository<FundamentalsSnapshot, String> {

	@Query("select max(s.fetchedAt) from FundamentalsSnapshot s")
	Instant latestFetchedAt();

	/**
	 * Insert-or-update in one statement. A plain {@code save} of an assigned-id entity does a select-then-insert
	 * that two concurrent refreshes of the same ticker (the boot fill and Agent 11's evidence step) can both
	 * lose to a duplicate-key error; this cannot.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			insert into fundamentals_snapshot (ticker, payload, applicable, score, bias, fetched_at)
			values (:ticker, :payload, :applicable, :score, :bias, :fetchedAt)
			on conflict (ticker) do update set payload = excluded.payload, applicable = excluded.applicable,
			    score = excluded.score, bias = excluded.bias, fetched_at = excluded.fetched_at""", nativeQuery = true)
	void upsert(@Param("ticker") String ticker, @Param("payload") String payload, @Param("applicable") boolean applicable,
			@Param("score") double score, @Param("bias") String bias, @Param("fetchedAt") Instant fetchedAt);
}
