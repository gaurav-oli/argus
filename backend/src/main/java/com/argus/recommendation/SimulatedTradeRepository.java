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

	/** Stop-outs (STOP / TRAILING_STOP) since {@code since} — the circuit breaker's count. */
	long countByExitReasonInAndClosedAtAfter(java.util.Collection<String> reasons, java.time.Instant since);

	/** A recent stop-out on this exact thesis — the re-entry cooldown. */
	boolean existsByTickerAndDirectionAndExitReasonInAndClosedAtAfter(String ticker, SignalDirection direction,
			java.util.Collection<String> reasons, java.time.Instant since);

	/** Every open leg on a ticker, whatever its direction or era — what a new Agent 5 call is checked against. */
	List<SimulatedTrade> findByTickerAndStatus(String ticker, SimulatedTrade.Status status);

	/** Early exits still waiting for their hold-to-horizon counterfactual. */
	List<SimulatedTrade> findByStatusAndHoldReturnPctIsNullAndExitReasonNot(SimulatedTrade.Status status, String exitReason);

	/** Thesis-level dedup — is this (ticker, direction, horizon) leg already on the open book? — limited to legs opened on/after {@code since} (see PaperInvestorService.NEW_SYSTEM_SINCE). */
	boolean existsByTickerAndDirectionAndHorizonDaysAndStatusAndEntryAtGreaterThanEqual(String ticker,
			SignalDirection direction, int horizonDays, SimulatedTrade.Status status, java.time.Instant since);

	/** The open legs of a thesis opened on/after {@code since}, for re-affirmation. */
	List<SimulatedTrade> findByTickerAndDirectionAndStatusAndEntryAtGreaterThanEqual(String ticker,
			SignalDirection direction, SimulatedTrade.Status status, java.time.Instant since);
}
