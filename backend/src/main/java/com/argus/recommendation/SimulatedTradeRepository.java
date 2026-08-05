package com.argus.recommendation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the Investor persona's paper-trading book ({@link SimulatedTrade}). */
public interface SimulatedTradeRepository extends JpaRepository<SimulatedTrade, Long> {

	List<SimulatedTrade> findByStatus(SimulatedTrade.Status status);

	List<SimulatedTrade> findByStatusOrderByClosedAtDesc(SimulatedTrade.Status status);

	long countByStatus(SimulatedTrade.Status status);

	/** Newest trades first, for the scoreboard's recent activity + equity curve. */
	List<SimulatedTrade> findTop100ByOrderByIdDesc();

	/** Avoid opening a duplicate simulated position for the same recommendation. */
	boolean existsByRecommendationId(Long recommendationId);

	/** Whether the Investor ever traded this (ticker, direction) thesis at all, regardless of which
	 * recommendation originally opened it or the leg's current status — used by the historical decision
	 * backfill, since a repeat recommendation that only re-affirmed an already-open thesis never gets
	 * its own {@code simulated_trades} row (the leg keeps the id of whichever recommendation opened it
	 * first), even though the Investor was genuinely acting on that call too. */
	boolean existsByTickerAndDirection(String ticker, SignalDirection direction);

	/** Thesis-level dedup: is this (ticker, direction, horizon) leg already on the open book? */
	boolean existsByTickerAndDirectionAndHorizonDaysAndStatus(String ticker, SignalDirection direction,
			int horizonDays, SimulatedTrade.Status status);

	/** The open legs of a thesis, for re-affirmation when a repeat recommendation arrives. */
	List<SimulatedTrade> findByTickerAndDirectionAndStatus(String ticker, SignalDirection direction,
			SimulatedTrade.Status status);
}
