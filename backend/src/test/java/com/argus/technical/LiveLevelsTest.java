package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Support/resistance re-measured from a live price: the stored study is measured from the last daily
 * close, so a stock that broke a level today must not be shown on the wrong side of it until tonight's
 * candle — and an implausible live quote (a wrong listing) must never move the levels at all.
 */
class LiveLevelsTest {

	private static final LocalDate START = LocalDate.of(2026, 1, 1);

	/** A zig-zag: swing lows at 90 and 95, swing highs at 110 and 105, last close 100. */
	private static List<PriceCandle> zigzag() {
		double[] closes = new double[60];
		for (int i = 0; i < 60; i++) {
			closes[i] = 100;
		}
		List<PriceCandle> out = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			double hi = closes[i] + 1, lo = closes[i] - 1;
			if (i == 30) lo = 90;
			if (i == 36) hi = 110;
			if (i == 42) lo = 95;
			if (i == 48) hi = 105;
			out.add(new PriceCandle("T", START.plusDays(i), bd(closes[i]), bd(hi), bd(lo), bd(closes[i]), 1_000_000L));
		}
		return out;
	}

	private static BigDecimal bd(double v) {
		return BigDecimal.valueOf(v);
	}

	@Test
	void levelsFollowThePriceTheyAreMeasuredFrom() {
		ChartStudy.Levels atClose = ChartReader.levels(zigzag(), 100);
		assertEquals(95.0, atClose.support());
		assertEquals(105.0, atClose.resistance());

		// Broke above 105 today: 105 is no longer resistance, 110 is — and 105 is not support either
		// (only swing LOWS are support), so the nearest support stays 95.
		ChartStudy.Levels brokeOut = ChartReader.levels(zigzag(), 107);
		assertEquals(95.0, brokeOut.support());
		assertEquals(110.0, brokeOut.resistance());

		// Broke below 95: the next support down is 90.
		ChartStudy.Levels brokeDown = ChartReader.levels(zigzag(), 93);
		assertEquals(90.0, brokeDown.support());
		assertEquals(105.0, brokeDown.resistance());
	}

	@Test
	void withLevelsRewritesTheLevelsNoteFromTheNewPrice() {
		ChartStudy study = ChartReader.study(zigzag(), List.of()).orElseThrow();
		ChartStudy live = study.withLevels(ChartReader.levels(zigzag(), 107), 107);

		assertEquals(110.0, live.resistance());
		assertTrue(live.notes().stream().anyMatch(n -> n.startsWith("Levels:") && n.contains("110.00")));
		assertEquals(study.score(), live.score(), "only the levels move — the rest of the study is untouched");
	}

	@Test
	void anImplausibleLiveQuoteNeverMovesTheLevels() {
		PriceCandleRepository repo = mock(PriceCandleRepository.class);
		List<PriceCandle> desc = new ArrayList<>(zigzag());
		java.util.Collections.reverse(desc);
		when(repo.findTop300ByTickerOrderByCandleDateDesc("T")).thenReturn(desc);
		ChartStudyService service = new ChartStudyService(repo);
		ChartStudy study = service.studyFor("T").orElseThrow();

		assertSame(study, service.withLivePrice(study, "T", null), "no live price: the close-based study");
		assertSame(study, service.withLivePrice(study, "T", 40.0), "a 60% gap is a wrong quote, not a move");
		assertFalse(ChartStudyService.livePriceUsable(study, 0.0));
		assertEquals(110.0, service.studyFor("T", 107.0).orElseThrow().resistance());
	}

	@Test
	void noLevelAboveAnAllTimeHigh() {
		assertNull(ChartReader.levels(zigzag(), 120).resistance());
	}
}
