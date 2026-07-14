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

	/** Thesis-level dedup: is this (ticker, direction, horizon) leg already on the open book? */
	boolean existsByTickerAndDirectionAndHorizonDaysAndStatus(String ticker, SignalDirection direction,
			int horizonDays, SimulatedTrade.Status status);

	/** The open legs of a thesis, for re-affirmation when a repeat recommendation arrives. */
	List<SimulatedTrade> findByTickerAndDirectionAndStatus(String ticker, SignalDirection direction,
			SimulatedTrade.Status status);
}
