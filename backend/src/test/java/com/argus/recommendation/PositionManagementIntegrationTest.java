package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import com.argus.model.ModelGateway;
import com.argus.technical.LivePriceService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Active management against real Postgres: the stored trades change exactly as the rules say. No candles are
 * stored, so Agent 10 has no ATR and the rules use their 3% default.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PositionManagementIntegrationTest {

	@Autowired
	PositionManager manager;

	@Autowired
	PaperInvestorService investor;

	@Autowired
	SimulatedTradeRepository trades;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	LivePriceService prices;

	@MockitoBean
	ModelGateway gateway; // losing closes ask for a post-mortem — never a real model call from a test

	@BeforeEach
	void clean() {
		jdbc.update("delete from simulated_trades");
		when(gateway.generate(anyString())).thenReturn("noise");
	}

	private void price(String ticker, String p) {
		when(prices.latestPrice(ticker)).thenReturn(Optional.of(new BigDecimal(p)));
	}

	/** A long AAPL leg entered at 100, {@code daysAgo} days ago. */
	private SimulatedTrade longAapl(Double stop, Double target, int daysAgo) {
		SimulatedTrade t = new SimulatedTrade(null, "AAPL", SignalDirection.BULLISH, new BigDecimal("100"), new BigDecimal("100"), 30, null);
		if (stop != null) {
			t.applyRisk(BigDecimal.valueOf(stop), 1.0);
		}
		t.setHighWater(new BigDecimal("100"));
		t.setTargetPrice(target == null ? null : BigDecimal.valueOf(target));
		t = trades.save(t);
		jdbc.update("update simulated_trades set entry_at = now() - make_interval(days => ?) where id = ?", daysAgo, t.getId());
		return trades.findById(t.getId()).orElseThrow();
	}

	private SimulatedTrade reload(SimulatedTrade t) {
		return trades.findById(t.getId()).orElseThrow();
	}

	@Test
	void aLegacyTradeWithNoStopGetsOneFromTodaysPrice() {
		SimulatedTrade t = longAapl(null, null, 20);
		price("AAPL", "80"); // already well under its 100 entry

		manager.manageAll(Instant.now());

		SimulatedTrade after = reload(t);
		assertEquals(SimulatedTrade.Status.OPEN, after.getStatus(), "protected from here on, not closed for past losses");
		assertTrue(after.getStopPrice().doubleValue() < 80 && after.getStopPrice().doubleValue() >= 80 * 0.85,
				"a 5–15% chart stop below today's 80: " + after.getStopPrice());
		assertEquals(0, after.getStopPrice().compareTo(after.getInitialStop()));
	}

	@Test
	void aStopOutClosesAtTheLivePriceNotTheStop() {
		SimulatedTrade t = longAapl(95.0, null, 2);
		price("AAPL", "91"); // gapped through 95

		manager.manageAll(Instant.now());

		SimulatedTrade after = reload(t);
		assertEquals(SimulatedTrade.Status.CLOSED, after.getStatus());
		assertEquals("STOP", after.getExitReason());
		assertEquals(0, new BigDecimal("91").compareTo(after.getExitPrice()), "an honest fill at the gapped price");
	}

	@Test
	void theTargetTakesHalfOffAndTheRestKeepsRunningFromBreakeven() {
		SimulatedTrade t = longAapl(92.0, 105.0, 3);
		price("AAPL", "106");

		manager.manageAll(Instant.now());

		SimulatedTrade rest = reload(t);
		assertEquals(SimulatedTrade.Status.OPEN, rest.getStatus());
		assertTrue(rest.isScaledOut());
		assertEquals(0, new BigDecimal("50.00").compareTo(rest.getNotional()));
		assertTrue(rest.getStopPrice().doubleValue() >= 100.0, "the rest trails from at least breakeven");
		List<SimulatedTrade> halves = trades.findAll().stream().filter(x -> t.getId().equals(x.getParentTradeId())).toList();
		assertEquals(1, halves.size());
		assertEquals("TAKE_PROFIT", halves.get(0).getExitReason());
		assertEquals(SimulatedTrade.Status.CLOSED, halves.get(0).getStatus());
		assertEquals(0, new BigDecimal("6.0000").compareTo(halves.get(0).getReturnPct()));
	}

	@Test
	void aRisingTradesStopIsRatchetedAndPersisted() {
		SimulatedTrade t = longAapl(92.0, null, 3);
		price("AAPL", "112");

		manager.manageAll(Instant.now());

		SimulatedTrade after = reload(t);
		assertEquals(0, new BigDecimal("112.000000").compareTo(after.getHighWater()));
		assertEquals(112 * (1 - 2.5 * 0.03), after.getStopPrice().doubleValue(), 1e-6, "2.5 × the 3% default ATR behind 112");
		assertTrue(after.isStopTrailed());
	}

	@Test
	void anOppositeAgent5CallExitsTheLegAsThesisDecay() {
		SimulatedTrade t = longAapl(90.0, null, 3);
		price("AAPL", "101");
		Recommendation bearish = jdbcRecommendation("AAPL", "STRONG_AVOID", "BEARISH");

		investor.reviewOpenAgainst(bearish);

		assertEquals("THESIS_DECAY", reload(t).getExitReason());
	}

	@Test
	void aRecentStopOutCoolsDownReEntryAndThreeTripTheBreaker() {
		SimulatedTrade stopped = longAapl(95.0, null, 2);
		price("AAPL", "94");
		manager.manageAll(Instant.now());
		assertEquals("STOP", reload(stopped).getExitReason());

		assertTrue(trades.existsByTickerAndDirectionAndExitReasonInAndClosedAtAfter("AAPL", SignalDirection.BULLISH,
				PaperInvestorService.STOP_OUTS, Instant.now().minus(PaperInvestorService.REENTRY_COOLDOWN)), "cooldown in force");
		assertEquals(1, trades.countByExitReasonInAndClosedAtAfter(PaperInvestorService.STOP_OUTS,
				Instant.now().minus(PaperInvestorService.BREAKER_WINDOW)));
	}

	@Test
	void anEarlyExitGetsItsHoldToHorizonCounterfactualWhenTheHorizonPasses() {
		SimulatedTrade t = longAapl(95.0, null, 31); // 30-day leg, horizon passed yesterday
		jdbc.update("update simulated_trades set status = 'CLOSED', exit_reason = 'STOP', exit_price = 94, return_pct = -6, won = false, closed_at = now() - interval '20 days' where id = ?", t.getId());
		price("AAPL", "120");

		investor.recordHoldCounterfactuals(Instant.now());

		SimulatedTrade after = reload(t);
		assertNotNull(after.getHoldReturnPct());
		assertEquals(0, new BigDecimal("20.0000").compareTo(after.getHoldReturnPct()), "holding would have made +20%");
	}

	@Autowired
	RecommendationRepository recommendations;

	/** A minimal stored recommendation with a verdict, via SQL (the entity's constructor needs a full score). */
	private Recommendation jdbcRecommendation(String ticker, String action, String direction) {
		Long id = jdbc.queryForObject("""
				insert into recommendations (ticker, direction, bull_probability, bear_probability, confidence, status, action, created_at)
				values (?, ?, 0.3, 0.7, 0.6, 'PENDING', ?, now()) returning id""", Long.class, ticker, direction, action);
		return recommendations.findById(id).orElseThrow();
	}
}
