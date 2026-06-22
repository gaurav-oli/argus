package com.argus.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for the Valet payload parsing / nearest-prior-business-day logic (Story 3.2). */
class BankOfCanadaFxClientTest {

	private static final String BODY = """
			{"observations":[
			  {"d":"2023-01-12","FXUSDCAD":{"v":"1.3400"}},
			  {"d":"2023-01-13","FXUSDCAD":{"v":"1.3413"}}
			]}""";

	@Test
	void takesTheObservationOnTheRequestedDate() {
		Optional<BigDecimal> rate = BankOfCanadaFxClient.parseLatest(BODY, LocalDate.of(2023, 1, 13));
		assertTrue(rate.isPresent());
		assertEquals(0, rate.get().compareTo(new BigDecimal("1.3413")));
	}

	@Test
	void fallsBackToNearestPriorBusinessDay() {
		// 2023-01-14 is a Saturday with no observation → use Friday the 13th.
		Optional<BigDecimal> rate = BankOfCanadaFxClient.parseLatest(BODY, LocalDate.of(2023, 1, 14));
		assertTrue(rate.isPresent());
		assertEquals(0, rate.get().compareTo(new BigDecimal("1.3413")));
	}

	@Test
	void neverUsesARateFromAfterTheRequestedDate() {
		Optional<BigDecimal> rate = BankOfCanadaFxClient.parseLatest(BODY, LocalDate.of(2023, 1, 12));
		assertTrue(rate.isPresent());
		assertEquals(0, rate.get().compareTo(new BigDecimal("1.3400"))); // not 1.3413 from the 13th
	}

	@Test
	void emptyOrMissingObservationsYieldEmpty() {
		assertTrue(BankOfCanadaFxClient.parseLatest("{\"observations\":[]}", LocalDate.of(2023, 1, 13)).isEmpty());
		assertTrue(BankOfCanadaFxClient.parseLatest("", LocalDate.of(2023, 1, 13)).isEmpty());
	}

	@Test
	void malformedJsonYieldsEmptyInsteadOfThrowing() {
		assertTrue(BankOfCanadaFxClient.parseLatest("not json at all", LocalDate.of(2023, 1, 13)).isEmpty());
		assertTrue(BankOfCanadaFxClient.parseLatest("{\"observations\":", LocalDate.of(2023, 1, 13)).isEmpty());
	}
}
