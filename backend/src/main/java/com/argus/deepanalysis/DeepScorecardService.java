package com.argus.deepanalysis;

import com.argus.technical.ChartStudyService;
import com.argus.technical.PriceCandle;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Reads every finished analysis and Agent 11's price history to produce the {@link DeepScorecard}, and derives the
 * {@link TrackRecord} the analyst uses to discount its own conviction once it has enough matured verdicts to be judged.
 */
@Service
public class DeepScorecardService {

	/** A track record is only acted on with at least this many matured verdicts of the kind — below that it is noise. */
	public static final int MIN_MATURED_FOR_TRACK_RECORD = 20;
	private static final Duration TTL = Duration.ofMinutes(10);

	private final DeepAnalysisRepository repository;
	private final ChartStudyService charts;
	private volatile Instant computedAt = Instant.EPOCH;
	private volatile DeepScorecard.Summary cached;
	private final Map<String, Boolean> lock = new ConcurrentHashMap<>();

	public DeepScorecardService(DeepAnalysisRepository repository, ChartStudyService charts) {
		this.repository = repository;
		this.charts = charts;
	}

	/** The full scorecard (cached briefly — it reads a year of candles for every analysed ticker). */
	public DeepScorecard.Summary summary() {
		DeepScorecard.Summary c = cached;
		if (c != null && computedAt.isAfter(Instant.now().minus(TTL))) {
			return c;
		}
		synchronized (lock) {
			c = cached;
			if (c != null && computedAt.isAfter(Instant.now().minus(TTL))) {
				return c;
			}
			cached = compute();
			computedAt = Instant.now();
			return cached;
		}
	}

	private DeepScorecard.Summary compute() {
		List<DeepAnalysis> done = repository.findByStatusIn(List.of(DeepAnalysis.Status.DONE));
		Map<String, List<DeepScorecard.Bar>> bars = new ConcurrentHashMap<>();
		List<DeepScorecard.Bar> spy = bars("SPY");
		List<DeepScorecard.Row> rows = new ArrayList<>();
		for (DeepAnalysis d : done) {
			if (d.getVerdict() == null || d.getFinishedAt() == null) continue;
			List<DeepScorecard.Bar> stock = bars.computeIfAbsent(d.getTicker(), this::bars);
			rows.add(DeepScorecard.evaluate(new DeepScorecard.Sample(d.getTicker(), d.getVerdict(), d.getFinishedAt().atZone(ZoneOffset.UTC).toLocalDate(),
					d.getPriceAtAnalysis() == null ? null : d.getPriceAtAnalysis().doubleValue()), stock, spy));
		}
		rows.sort((a, b) -> b.analyzedOn().compareTo(a.analyzedOn()));
		return DeepScorecard.summarize(rows);
	}

	private List<DeepScorecard.Bar> bars(String ticker) {
		List<DeepScorecard.Bar> out = new ArrayList<>();
		for (PriceCandle c : charts.history(ticker)) {
			out.add(new DeepScorecard.Bar(c.getCandleDate(), c.getClose().doubleValue()));
		}
		return out;
	}

	/**
	 * How Agent 11's {@code verdict} calls have done, using the longest horizon with enough matured samples (30 days preferred,
	 * then 7). Empty until there are {@value #MIN_MATURED_FOR_TRACK_RECORD} matured verdicts of that kind — the guard ignores
	 * anything thinner, so early noise cannot masquerade as a track record.
	 */
	public Optional<TrackRecord> trackRecord(DeepVerdict verdict) {
		Map<Integer, DeepScorecard.Cell> cells = summary().cells().getOrDefault(verdict, Map.of());
		for (int h : List.of(30, 7)) {
			DeepScorecard.Cell c = cells.get(h);
			if (c != null && c.n() >= MIN_MATURED_FOR_TRACK_RECORD && c.hitRate() != null) {
				return Optional.of(new TrackRecord(c.n(), c.hitRate(), c.meanExcessPct(), h));
			}
		}
		return Optional.empty();
	}
}
