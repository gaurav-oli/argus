package com.argus.deepanalysis;

import com.argus.common.BadRequestException;
import com.argus.intelligence.KnownUniverse;
import com.argus.intelligence.PortfolioKnownUniverse;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs Agent 11's analyses one at a time in the background. A full pass over the universe legitimately
 * takes hours (several LLM stages per ticker), and that is by design — the model is a shared, serialized
 * resource, so analyses queue rather than compete.
 *
 * <ul>
 *   <li><b>Nightly</b> (01:00 Toronto): enqueue every tracked ticker whose verdict is older than
 *       {@code refreshDays}, held tickers first, then the stalest.</li>
 *   <li><b>On demand</b>: {@link #enqueue} from the UI ("Analyze now") or another agent; one queued/running
 *       run per ticker at most.</li>
 *   <li><b>Boot</b>: work that was queued or mid-run when the process stopped is resumed, and a first-ever pass
 *       over the held tickers is started if no analysis exists yet, so a fresh deploy is useful within hours.</li>
 * </ul>
 */
@Component
public class DeepAnalysisRunner {

	private static final Logger log = LoggerFactory.getLogger(DeepAnalysisRunner.class);
	private static final Pattern TICKER = Pattern.compile("^[A-Z]{1,6}(\\.[A-Z])?$");

	private final DeepAnalysisRepository repository;
	private final DeepAnalystService analyst;
	private final DeepAnalysisProperties props;
	private final KnownUniverse universe;
	private final PortfolioKnownUniverse held;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "deep-analyst");
		t.setDaemon(true);
		return t;
	});

	public DeepAnalysisRunner(DeepAnalysisRepository repository, DeepAnalystService analyst, DeepAnalysisProperties props,
			KnownUniverse universe, PortfolioKnownUniverse held) {
		this.repository = repository;
		this.analyst = analyst;
		this.props = props;
		this.universe = universe;
		this.held = held;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Queue an analysis of {@code rawTicker}. If one is already queued or running for that ticker it is
	 * returned instead — never two at once. Deliberately not {@code @Transactional}: {@code save} commits
	 * before the executor picks the run up (same race Agent 9 documents).
	 */
	public DeepAnalysis enqueue(String rawTicker, String trigger) {
		String ticker = normalize(rawTicker);
		Optional<DeepAnalysis> existing = repository.findFirstByTickerAndStatusInOrderByCreatedAtDesc(ticker,
				List.of(DeepAnalysis.Status.QUEUED, DeepAnalysis.Status.RUNNING));
		if (existing.isPresent()) {
			return existing.get();
		}
		DeepAnalysis run = repository.save(new DeepAnalysis(ticker, trigger));
		submit(run.getId());
		return run;
	}

	private void submit(Long id) {
		executor.submit(() -> {
			try {
				analyst.analyze(id);
			}
			catch (RuntimeException ex) {
				log.warn("Agent 11: run {} crashed: {}", id, ex.getMessage());
			}
		});
	}

	private static String normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new BadRequestException("Ticker must not be empty.");
		}
		String t = raw.trim().toUpperCase(Locale.ROOT);
		if (!TICKER.matcher(t).matches()) {
			throw new BadRequestException("'" + raw + "' doesn't look like a valid ticker symbol.");
		}
		return t;
	}

	/** The tickers a nightly run should enqueue, in order: held first, then stalest. Package-visible for tests. */
	List<String> nightlyOrder() {
		Set<String> heldSet = new LinkedHashSet<>(held.knownTickers());
		Instant staleBefore = Instant.now().minus(Duration.ofDays(props.refreshDays()));
		List<String> due = new ArrayList<>();
		for (String t : universe.knownTickers()) {
			Instant last = repository.lastFinished(t);
			if (last == null || last.isBefore(staleBefore)) {
				due.add(t);
			}
		}
		due.sort(Comparator.comparing((String t) -> !heldSet.contains(t))
				.thenComparing(t -> Optional.ofNullable(repository.lastFinished(t)).orElse(Instant.EPOCH)));
		return due.size() > props.maxPerRun() ? due.subList(0, props.maxPerRun()) : due;
	}

	@Scheduled(cron = "${argus.deep-analysis.cron:0 0 1 * * *}", zone = "America/Toronto")
	public void nightly() {
		if (!props.enabled()) {
			return;
		}
		List<String> order = nightlyOrder();
		order.forEach(t -> enqueue(t, "NIGHTLY"));
		log.info("Agent 11: nightly pass queued {} ticker(s)", order.size());
	}

	/** Resume interrupted work; and give a fresh deploy a first pass over the held tickers. */
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!props.enabled()) {
			return;
		}
		Thread.startVirtualThread(() -> {
			try {
				List<DeepAnalysis> interrupted = repository.findByStatusIn(List.of(DeepAnalysis.Status.QUEUED, DeepAnalysis.Status.RUNNING));
				for (DeepAnalysis d : interrupted) {
					if (d.getStatus() == DeepAnalysis.Status.RUNNING) {
						d.fail("Interrupted by a restart — re-queued.");
						repository.save(d);
						enqueue(d.getTicker(), "BOOT");
					}
					else {
						submit(d.getId());
					}
				}
				if (interrupted.isEmpty() && repository.count() == 0) {
					held.knownTickers().stream().sorted().forEach(t -> enqueue(t, "BOOT"));
					log.info("Agent 11: first-ever pass queued for the held tickers");
				}
			}
			catch (RuntimeException ex) {
				log.warn("Agent 11: startup recovery failed: {}", ex.getMessage());
			}
		});
	}
}
