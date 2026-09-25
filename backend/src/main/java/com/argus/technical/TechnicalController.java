package com.argus.technical;

import com.argus.common.NotFoundException;
import com.argus.intelligence.KnownUniverse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 10's chart studies for the Intelligence page, session-gated under {@code /api/technical}: a ranked list
 * of every tracked ticker's chart read, and a per-ticker detail carrying the candles and moving-average series
 * so the UI can draw the actual candlestick chart the study was computed from.
 */
@RestController
@RequestMapping("/api/technical")
public class TechnicalController {

	private static final int CHART_BARS = 120;

	private final ChartStudyService charts;
	private final KnownUniverse universe;

	public TechnicalController(ChartStudyService charts, KnownUniverse universe) {
		this.charts = charts;
		this.universe = universe;
	}

	/** Every tracked ticker with enough history, the most decisive charts (either direction) first. */
	@GetMapping("/studies")
	public List<StudyRow> studies() {
		List<StudyRow> rows = new ArrayList<>();
		for (String t : universe.knownTickers()) {
			charts.studyFor(t).ifPresent(s -> rows.add(StudyRow.from(t, s)));
		}
		rows.sort(Comparator.comparingDouble((StudyRow r) -> Math.abs(r.score())).reversed());
		return rows;
	}

	@GetMapping("/{ticker}")
	public ChartDetail detail(@PathVariable String ticker) {
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		ChartStudy study = charts.studyFor(t).orElseThrow(() -> new NotFoundException("Chart study", t));
		List<PriceCandle> all = charts.history(t);
		int from = Math.max(0, all.size() - CHART_BARS);
		List<Bar> bars = new ArrayList<>();
		List<Point> sma20 = new ArrayList<>(), sma50 = new ArrayList<>(), sma200 = new ArrayList<>();
		for (int i = from; i < all.size(); i++) {
			PriceCandle c = all.get(i);
			bars.add(new Bar(c.getCandleDate(), c.getOpen().doubleValue(), c.getHigh().doubleValue(), c.getLow().doubleValue(), c.getClose().doubleValue(),
					c.getVolume() == null ? 0 : c.getVolume()));
			sma(all, i, 20).ifPresent(v -> sma20.add(new Point(c.getCandleDate(), v)));
			sma(all, i, 50).ifPresent(v -> sma50.add(new Point(c.getCandleDate(), v)));
			sma(all, i, 200).ifPresent(v -> sma200.add(new Point(c.getCandleDate(), v)));
		}
		return new ChartDetail(StudyRow.from(t, study), study.notes(), study.support(), study.resistance(), bars, sma20, sma50, sma200);
	}

	private static Optional<Double> sma(List<PriceCandle> all, int index, int period) {
		if (index + 1 < period) return Optional.empty();
		double sum = 0;
		for (int k = index - period + 1; k <= index; k++) sum += all.get(k).getClose().doubleValue();
		return Optional.of(sum / period);
	}

	public record StudyRow(String ticker, LocalDate asOf, double lastClose, String bias, double score, String trend, Double ret5d, Double ret20d,
			Double rsi14, Double relStrength60d, List<String> patterns, Double support, Double resistance, List<String> headlineNotes) {

		static StudyRow from(String ticker, ChartStudy s) {
			return new StudyRow(ticker, s.asOf(), s.lastClose(), s.bias(), s.score(), s.trend().name(), s.ret5d(), s.ret20d(), s.rsi14(), s.relStrength60d(),
					s.patterns().stream().map(p -> p.name() + " (" + p.bias().toLowerCase(Locale.ROOT) + ")").toList(), s.support(), s.resistance(),
					s.notes().stream().limit(3).toList());
		}
	}

	public record Bar(LocalDate time, double open, double high, double low, double close, long volume) {
	}

	public record Point(LocalDate time, double value) {
	}

	public record ChartDetail(StudyRow study, List<String> notes, Double support, Double resistance, List<Bar> candles, List<Point> sma20,
			List<Point> sma50, List<Point> sma200) {
	}
}
