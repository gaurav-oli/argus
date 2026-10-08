package com.argus.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.argus.TestcontainersConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** "Re-review open trades now" reports, per ticker, what Agent 5's fresh look did to the book. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OpenTradeReviewIntegrationTest {

	@Autowired
	OpenTradeReview review;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	RecommendationTrigger trigger;

	@MockitoBean
	GraduationService graduation;

	@BeforeEach
	void book() {
		jdbc.update("delete from simulated_trades");
		jdbc.update("insert into simulated_trades (ticker, direction, notional, entry_price, shares, horizon_days, status, stop_price) values "
				+ "('SMCI', 'BEARISH', 100, 40, 2.5, 30, 'OPEN', 44), ('MU', 'BULLISH', 100, 1000, 0.1, 30, 'OPEN', 950)");
		when(graduation.currentState()).thenReturn(GraduationState.SHADOW);
		// Agent 5's fresh look: reverses SMCI (its bearish leg exits), only watches MU (its stop tightens).
		when(trigger.trigger(eq("SMCI"), anyString())).thenAnswer(i -> {
			jdbc.update("update simulated_trades set status = 'CLOSED', exit_reason = 'THESIS_DECAY', return_pct = -8.2 where ticker = 'SMCI'");
			return Optional.empty();
		});
		when(trigger.trigger(eq("MU"), anyString())).thenAnswer(i -> {
			jdbc.update("update simulated_trades set stop_price = 1010 where ticker = 'MU'");
			return Optional.empty();
		});
	}

	@Test
	void reportsExitsAndTightenedStopsPerTicker() {
		OpenTradeReview.ReviewResult r = review.reviewAll();

		assertEquals(2, r.tickers());
		assertEquals(1, r.exited());
		assertEquals(1, r.stopsTightened());
		OpenTradeReview.TickerResult smci = r.results().stream().filter(x -> x.ticker().equals("SMCI")).findFirst().orElseThrow();
		assertEquals("bearish 30-day trade · call reversed · -8.2%", smci.exited().get(0));
	}

	@Test
	void aFrozenAgent5ReviewsNothing() {
		when(graduation.currentState()).thenReturn(GraduationState.FROZEN);

		assertTrue(review.reviewAll().frozen());
		verify(trigger, never()).trigger(anyString(), anyString());
	}

	@Test
	void theBackgroundJobReportsProgressAndFinishesWithEveryResult() throws Exception {
		OpenTradeReview.JobStatus started = review.start();
		assertTrue(started.finishedAt() == null, "starts running");
		OpenTradeReview.JobStatus st = started;
		for (int i = 0; i < 100 && (st = review.status()).finishedAt() == null; i++) {
			Thread.sleep(50);
		}
		assertEquals(OpenTradeReview.Step.DONE, st.step());
		assertEquals(2, st.total());
		assertEquals(2, st.done());
		assertEquals(2, st.results().size());
		assertEquals(1, st.exited());
		assertEquals(started.id(), st.id());
	}
}
