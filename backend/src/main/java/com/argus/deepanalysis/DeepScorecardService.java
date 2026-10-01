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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reads every finished analysis and Agent 11's price history to produce the {@link DeepScorecard}, and derives the
 * {@link TrackRecord} the analyst uses to discount its own conviction once it has enough matured verdicts to be judged.
 *
 * <p>The live computation is cached briefly ({@value #TTL}) for the hot read path, but that cache is memory-only and
 * forgets everything on a restart. {@link #snapshotNow()} additionally persists one row per (verdict, horizon) cell
 * to {@link DeepScorecardSnapshot} — not instead of the cache, alongside it — so Agent 11's track record survives a
 * restart and its history over time can be read back ({@link #history()}), not just "right now."
 */
@Service
public class DeepScorecardService {

	private static final Logger log = LoggerFactory.getLogger(DeepScorecardService.class);
	/** A track record is only acted on with at least this many matured verdicts of the kind — below that it is noise. */
	public static final int MIN_MATURED_FOR_TRACK_RECORD = 20;
	private static final Duration TTL = Duration.ofMinutes(10);

	private final DeepAnalysisRepository repository;
	private final ChartStudyService charts;
	private final DeepScorecardSnapshotRepository snapshots;
	private final DeepAnalysisProperties props;
	private volatile Instant computedAt = Instant.EPOCH;
	private volatile DeepScorecard.Summary cached;
	private final Map<String, Boolean> lock = new ConcurrentHashMap<>();

	public DeepScorecardService(DeepAnalysisRepository repository, ChartStudyService charts,
			DeepScorecardSnapshotRepository snapshots, DeepAnalysisProperties props) {
		this.repository = repository;
		this.charts = charts;
		this.snapshots = snapshots;
		this.props = props;
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

	/**
	 * Persist the current scorecard as one new row per (verdict, horizon) cell with matured observations — an
	 * append, never an overwrite, so the history is real. Returns how many rows were written. A cell with zero
	 * matured observations is skipped: an empty reading is not a data point worth keeping.
	 */
	public int snapshotNow() {
		DeepScorecard.Summary s = summary();
		int written = 0;
		for (Map.Entry<DeepVerdict, Map<Integer, DeepScorecard.Cell>> byVerdict : s.cells().entrySet()) {
			for (Map.Entry<Integer, DeepScorecard.Cell> byHorizon : byVerdict.getValue().entrySet()) {
				if (byHorizon.getValue().n() == 0) continue;
				snapshots.save(new DeepScorecardSnapshot(byVerdict.getKey(), byHorizon.getKey(), byHorizon.getValue(), s.totalVerdicts()));
				written++;
			}
		}
		if (written > 0) {
			log.info("Agent 11: scorecard snapshot saved — {} cell(s), {} total verdict(s)", written, s.totalVerdicts());
		}
		return written;
	}

	/** The full saved history, oldest first — how Agent 11's own track record has moved over time, not just today's reading. */
	public List<DeepScorecardSnapshot> history() {
		return snapshots.findAllByOrderByComputedAtAsc();
	}

	/** Daily, well after the nightly analysis run and the hourly thesis checks have had their say for the day. */
	@Scheduled(cron = "${argus.deep-analysis.scorecard-snapshot-cron:0 0 2 * * *}", zone = "America/Toronto")
	public void scheduledSnapshot() {
		if (!props.enabled()) {
			return;
		}
		try {
			snapshotNow();
		}
		catch (RuntimeException ex) {
			log.warn("Agent 11: scorecard snapshot failed: {}", ex.getMessage());
		}
	}

	/** First-ever snapshot on a fresh deploy, so the history doesn't sit empty until the next 2am run. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!props.enabled()) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				if (snapshots.count() == 0) {
					snapshotNow();
				}
			}
			catch (RuntimeException ex) {
				log.warn("Agent 11: first-boot scorecard snapshot failed: {}", ex.getMessage());
			}
		});
	}
}
