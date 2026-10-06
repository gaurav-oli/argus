package com.argus.technical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.argus.common.NotFoundException;
import com.argus.intelligence.KnownUniverse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TechnicalControllerTest {

	private final ChartStudyService charts = mock(ChartStudyService.class);
	private final KnownUniverse universe = mock(KnownUniverse.class);
	private final LivePriceService livePrices = mock(LivePriceService.class);
	private final TechnicalController controller = new TechnicalController(charts, universe, livePrices);

	private static ChartStudy study(double score) {
		return new ChartStudy(250, LocalDate.of(2026, 9, 24), 100, 1.0, 2.0, 3.0, 100.0, 98.0, 90.0, ChartStudy.Trend.UPTREND, 55.0, 0.5, 0.5, 2.0,
				1.0, 1.2, 50.0, -3.0, 95.0, 105.0, List.of(new ChartStudy.CandlePattern(LocalDate.of(2026, 9, 23), "Hammer", "BULLISH", "after a decline")),
				1.0, 2.0, score, score > 0 ? "BULLISH" : "BEARISH", List.of("Trend: UPTREND", "Returns: 5d +1%", "Momentum: RSI 55", "Volume: fine"));
	}

	@Test
	void theStudiesListRanksTheMostDecisiveChartsFirstInEitherDirection() {
		when(universe.knownTickers()).thenReturn(new LinkedHashSet<>(List.of("AAA", "BBB", "CCC", "DDD")));
		when(charts.studyFor("AAA", null)).thenReturn(Optional.of(study(0.3)));
		when(charts.studyFor("BBB", null)).thenReturn(Optional.of(study(-0.8)));
		when(charts.studyFor("CCC", null)).thenReturn(Optional.of(study(0.6)));
		when(charts.studyFor("DDD", null)).thenReturn(Optional.empty()); // not enough history

		List<TechnicalController.StudyRow> rows = controller.studies();

		assertEquals(List.of("BBB", "CCC", "AAA"), rows.stream().map(TechnicalController.StudyRow::ticker).toList());
		assertEquals("Hammer (bullish)", rows.get(1).patterns().get(0));
		assertEquals(3, rows.get(0).headlineNotes().size());
	}

	@Test
	void theDetailCarriesCandlesAndMovingAverageSeriesForTheChart() {
		List<PriceCandle> history = new ArrayList<>();
		for (int i = 0; i < 260; i++) {
			BigDecimal p = BigDecimal.valueOf(100 + i * 0.1);
			history.add(new PriceCandle("NVDA", LocalDate.of(2025, 10, 1).plusDays(i), p, p, p, p, 1000L));
		}
		when(charts.studyFor("NVDA", null)).thenReturn(Optional.of(study(0.5)));
		when(charts.history("NVDA")).thenReturn(history);

		TechnicalController.ChartDetail d = controller.detail("nvda");

		assertEquals(120, d.candles().size(), "the last 120 bars are charted");
		assertEquals(120, d.sma20().size());
		assertEquals(61, d.sma200().size(), "the 200-day average only exists from bar 200: 260 bars → 61 points inside the 120-bar window");
		assertTrue(d.sma20().get(119).value() > d.sma200().get(60).value(), "in an uptrend the short average is above the long one");
		assertEquals(95.0, d.support());
	}

	@Test
	void theDetailSaysWhetherItsLevelsAreMeasuredFromTheLivePrice() {
		when(livePrices.livePrice("NVDA")).thenReturn(Optional.of(103.0));
		when(charts.studyFor("NVDA", 103.0)).thenReturn(Optional.of(study(0.5)));
		when(charts.history("NVDA")).thenReturn(List.of());

		TechnicalController.ChartDetail live = controller.detail("NVDA");
		assertTrue(live.levelsLive());
		assertEquals(103.0, live.levelsPrice());

		when(livePrices.livePrice("NVDA")).thenReturn(Optional.empty());
		when(charts.studyFor("NVDA", null)).thenReturn(Optional.of(study(0.5)));
		TechnicalController.ChartDetail atClose = controller.detail("NVDA");
		assertTrue(!atClose.levelsLive());
		assertEquals(100.0, atClose.levelsPrice(), "no live price: measured from the last close");
	}

	@Test
	void anUnknownOrTooYoungTickerIs404() {
		when(charts.studyFor("ZZZ", null)).thenReturn(Optional.empty());

		assertThrows(NotFoundException.class, () -> controller.detail("zzz"));
	}
}
