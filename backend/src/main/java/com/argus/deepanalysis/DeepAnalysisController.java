package com.argus.deepanalysis;

import com.argus.common.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Agent 11 endpoints for the Intelligence page, session-gated under {@code /api/deep-analysis}. */
@RestController
@RequestMapping("/api/deep-analysis")
public class DeepAnalysisController {

	private final DeepAnalysisService service;
	private final DeepAnalysisRunner runner;
	private final DeepAnalysisRepository repository;
	private final DeepScorecardService scorecard;

	public DeepAnalysisController(DeepAnalysisService service, DeepAnalysisRunner runner, DeepAnalysisRepository repository,
			DeepScorecardService scorecard) {
		this.service = service;
		this.runner = runner;
		this.repository = repository;
		this.scorecard = scorecard;
	}

	/** How Agent 11's past verdicts actually did against the S&P 500 (7/30/90 days) and since the call. */
	@GetMapping("/scorecard")
	public ScorecardView scorecard() {
		DeepScorecard.Summary s = scorecard.summary();
		List<CellView> cells = new java.util.ArrayList<>();
		s.cells().forEach((verdict, byHorizon) -> byHorizon.forEach((h, c) -> cells.add(new CellView(verdict.name(), verdict.label(), h, c.n(),
				Math.round(c.meanExcessPct() * 100) / 100.0, c.hitRate() == null ? null : Math.round(c.hitRate() * 1000) / 1000.0))));
		List<RowView> rows = s.rows().stream().limit(60).map(r -> new RowView(r.ticker(), r.verdict().name(), r.analyzedOn().toString(), r.entryPrice(),
				r.sincePct() == null ? null : Math.round(r.sincePct() * 100) / 100.0,
				r.sinceExcessPct() == null ? null : Math.round(r.sinceExcessPct() * 100) / 100.0, r.matured())).toList();
		return new ScorecardView(s.totalVerdicts(), DeepScorecardService.MIN_MATURED_FOR_TRACK_RECORD, cells, rows);
	}

	/** Every ticker's newest verdict (or in-progress run), strongest buys first. */
	@GetMapping
	public List<AnalysisView> list() {
		return repository.latestDonePerTicker().stream().map(d -> AnalysisView.from(d, statusOf(d.getTicker()))).sorted((a, b) -> {
			int byVerdict = Integer.compare(rank(a.verdict()), rank(b.verdict()));
			return byVerdict != 0 ? byVerdict : Integer.compare(b.conviction() == null ? 0 : b.conviction(), a.conviction() == null ? 0 : a.conviction());
		}).toList();
	}

	/** Queued and running analyses — what the deep analyst is working on right now. */
	@GetMapping("/queue")
	public QueueView queue() {
		List<DeepAnalysis> active = repository.findByStatusIn(List.of(DeepAnalysis.Status.QUEUED, DeepAnalysis.Status.RUNNING));
		return new QueueView(active.stream().filter(d -> d.getStatus() == DeepAnalysis.Status.RUNNING).map(d -> d.getTicker() + " — " + d.getStage())
				.findFirst().orElse(null), (int) active.stream().filter(d -> d.getStatus() == DeepAnalysis.Status.QUEUED).count(),
				active.stream().map(DeepAnalysis::getTicker).distinct().toList());
	}

	@GetMapping("/{ticker}")
	public TickerView get(@PathVariable String ticker) {
		String t = ticker.trim().toUpperCase(Locale.ROOT);
		List<DeepAnalysis> history = repository.findTop20ByTickerOrderByCreatedAtDesc(t);
		if (history.isEmpty()) {
			throw new NotFoundException("Deep analysis", t);
		}
		DeepAnalysis latestDone = service.latestDone(t).orElse(null);
		return new TickerView(latestDone == null ? null : AnalysisView.from(latestDone, null), statusOf(t),
				history.stream().map(h -> new HistoryItem(h.getId(), h.getStatus().name(), h.getVerdict() == null ? null : h.getVerdict().name(),
						h.getConviction(), h.getHoldDays(), h.getFinishedAt() == null ? h.getCreatedAt() : h.getFinishedAt())).toList());
	}

	/** "Analyze now" — queues a run and returns immediately; poll {@code /queue} or this ticker for progress. */
	@PostMapping("/{ticker}/run")
	public RunStatus run(@PathVariable String ticker) {
		DeepAnalysis run = runner.enqueue(ticker, "MANUAL");
		return new RunStatus(run.getId(), run.getTicker(), run.getStatus().name(), run.getStage());
	}

	private RunStatus statusOf(String ticker) {
		return repository.findFirstByTickerAndStatusInOrderByCreatedAtDesc(ticker, List.of(DeepAnalysis.Status.QUEUED, DeepAnalysis.Status.RUNNING))
				.map(d -> new RunStatus(d.getId(), d.getTicker(), d.getStatus().name(), d.getStage())).orElse(null);
	}

	private static int rank(String verdict) {
		return "WORTH_BUYING".equals(verdict) ? 0 : "WAIT".equals(verdict) ? 1 : 2;
	}

	public record CellView(String verdict, String label, int horizonDays, int n, double meanExcessPct, Double hitRate) {
	}

	public record RowView(String ticker, String verdict, String analyzedOn, Double entryPrice, Double sincePct, Double sinceExcessPct,
			java.util.Map<Integer, Double> matured) {
	}

	public record ScorecardView(int totalVerdicts, int minForTrackRecord, List<CellView> cells, List<RowView> rows) {
	}

	public record RunStatus(Long id, String ticker, String status, String stage) {
	}

	public record QueueView(String running, int queued, List<String> tickers) {
	}

	public record HistoryItem(Long id, String status, String verdict, Integer conviction, Integer holdDays, Instant at) {
	}

	public record TickerView(AnalysisView latest, RunStatus inProgress, List<HistoryItem> history) {
	}

	/** One finished analysis with all its reasoning. {@code inProgress} is set when a newer run is queued/running. */
	public record AnalysisView(Long id, String ticker, String verdict, String verdictLabel, Integer holdDays, Integer conviction, String headline,
			String thesis, String bullCase, String bearCase, List<String> risks, List<String> catalysts, String invalidation, String technicalSummary,
			String fundamentalSummary, String catalystSummary, String macroSummary, String skepticView, List<String> guardNotes,
			BigDecimal technicalScore, BigDecimal fundamentalScore, BigDecimal consensusScore, String model, Instant finishedAt, Instant expiresAt,
			boolean stale, RunStatus inProgress, BigDecimal invalidationPrice, BigDecimal priceAtAnalysis, String thesisStatus, String thesisReason) {

		static AnalysisView from(DeepAnalysis d, RunStatus inProgress) {
			return new AnalysisView(d.getId(), d.getTicker(), d.getVerdict() == null ? null : d.getVerdict().name(),
					d.getVerdict() == null ? null : d.getVerdict().label(), d.getHoldDays(), d.getConviction(), d.getHeadline(), d.getThesis(),
					d.getBullCase(), d.getBearCase(), lines(d.getRisks()), lines(d.getCatalysts()), d.getInvalidation(), d.getTechnicalSummary(),
					d.getFundamentalSummary(), d.getCatalystSummary(), d.getMacroSummary(), d.getSkepticView(), lines(d.getGuardNotes()),
					d.getTechnicalScore(), d.getFundamentalScore(), d.getConsensusScore(), d.getModel(), d.getFinishedAt(), d.getExpiresAt(),
					d.getExpiresAt() != null && d.getExpiresAt().isBefore(Instant.now()), inProgress, d.getInvalidationPrice(), d.getPriceAtAnalysis(), d.getThesisStatus(), d.getThesisReason());
		}

		private static List<String> lines(String joined) {
			return joined == null || joined.isBlank() ? List.of() : Arrays.stream(joined.split("\n")).filter(s -> !s.isBlank()).toList();
		}
	}
}
