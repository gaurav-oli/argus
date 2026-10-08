package com.argus.recommendation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Re-review open trades now": Agent 5 re-scores every ticker the paper book holds, on demand, and reports what
 * that did to each — the new call, legs exited (a reversed call), stops tightened (a WATCH). The same path as
 * the six-hourly pass and the change watcher, just run now for exactly the book.
 */
@Service
public class OpenTradeReview {

	private static final Logger log = LoggerFactory.getLogger(OpenTradeReview.class);

	private final SimulatedTradeRepository trades;
	private final RecommendationTrigger trigger;
	private final GraduationService graduation;

	public OpenTradeReview(SimulatedTradeRepository trades, RecommendationTrigger trigger, GraduationService graduation) {
		this.trades = trades;
		this.trigger = trigger;
		this.graduation = graduation;
	}

	/** One ticker's outcome. {@code call} is null when Agent 5 made none (quiet period, no signals). */
	public record TickerResult(String ticker, String call, Integer conviction, int openBefore, List<String> exited,
			int stopsTightened, int newLegs) {
	}

	public record ReviewResult(boolean frozen, int tickers, int exited, int stopsTightened, List<TickerResult> results) {
	}

	/** The pipeline's steps, in order. */
	public enum Step { LOADING, REVIEWING, SUMMARIZING, DONE, FROZEN, FAILED }

	/**
	 * A review's live status for the UI's pipeline: the step, the ticker being reviewed and what is happening to it,
	 * and every finished ticker's result so far. {@code results} grows as the review runs.
	 */
	public record JobStatus(long id, Step step, int total, int done, String currentTicker, String currentStage,
			List<TickerResult> results, Integer exited, Integer stopsTightened, java.time.Instant startedAt,
			java.time.Instant finishedAt, String error) {
	}

	private final java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();
	private volatile JobStatus current;

	/** Start a review in the background (or return the one already running). */
	public synchronized JobStatus start() {
		JobStatus running = current;
		if (running != null && running.finishedAt() == null) {
			return running;
		}
		long id = ids.incrementAndGet();
		current = new JobStatus(id, Step.LOADING, 0, 0, null, "Loading the open book", List.of(), null, null,
				java.time.Instant.now(), null, null);
		Thread.startVirtualThread(() -> {
			try {
				reviewAll();
			}
			catch (RuntimeException ex) {
				log.warn("Open-trade review {} failed: {}", id, ex.toString());
				update(st -> new JobStatus(st.id(), Step.FAILED, st.total(), st.done(), null, null, st.results(), null, null,
						st.startedAt(), java.time.Instant.now(), ex.getMessage()));
			}
		});
		return current;
	}

	/** The running or most recent review, or null if none has run since startup. */
	public JobStatus status() {
		return current;
	}

	private synchronized void update(java.util.function.UnaryOperator<JobStatus> change) {
		if (current != null) {
			current = change.apply(current);
		}
	}

	public ReviewResult reviewAll() {
		if (graduation.currentState() == GraduationState.FROZEN) {
			update(st -> new JobStatus(st.id(), Step.FROZEN, 0, 0, null, null, List.of(), 0, 0, st.startedAt(),
					java.time.Instant.now(), null));
			return new ReviewResult(true, 0, 0, 0, List.of());
		}
		Set<String> tickers = new LinkedHashSet<>();
		trades.findByStatus(SimulatedTrade.Status.OPEN).forEach(t -> tickers.add(t.getTicker()));
		update(st -> new JobStatus(st.id(), Step.REVIEWING, tickers.size(), 0, null, null, List.of(), null, null,
				st.startedAt(), null, null));
		List<TickerResult> results = new ArrayList<>();
		for (String ticker : tickers) {
			update(st -> new JobStatus(st.id(), st.step(), st.total(), st.done(), ticker,
					"Agent 5 re-scoring — news, price, chart, fundamentals, deep analysis", st.results(), null, null,
					st.startedAt(), null, null));
			Map<Long, BigDecimal> stopsBefore = openOn(ticker).stream()
					.collect(Collectors.toMap(SimulatedTrade::getId, t -> t.getStopPrice() == null ? BigDecimal.ZERO : t.getStopPrice()));
			Optional<Recommendation> rec;
			try {
				rec = trigger.trigger(ticker, "manual review of open trades");
			}
			catch (RuntimeException ex) {
				log.warn("Open-trade review of {} failed: {}", ticker, ex.toString());
				rec = Optional.empty();
			}
			Map<Long, SimulatedTrade> after = trades.findAllById(stopsBefore.keySet()).stream()
					.collect(Collectors.toMap(SimulatedTrade::getId, Function.identity()));
			List<String> exited = new ArrayList<>();
			int tightened = 0;
			for (Map.Entry<Long, BigDecimal> e : stopsBefore.entrySet()) {
				SimulatedTrade t = after.get(e.getKey());
				if (t == null) {
					continue;
				}
				if (t.getStatus() == SimulatedTrade.Status.CLOSED) {
					exited.add(t.getDirection().name().toLowerCase() + " " + t.getHorizonDays() + "-day trade · " + reasonLabel(t.getExitReason())
							+ (t.getReturnPct() == null ? "" : " · " + t.getReturnPct().setScale(1, java.math.RoundingMode.HALF_UP) + "%"));
				}
				else if (t.getStopPrice() != null && t.getStopPrice().compareTo(e.getValue()) != 0) {
					tightened++;
				}
			}
			int newLegs = (int) openOn(ticker).stream().filter(t -> !stopsBefore.containsKey(t.getId())).count();
			TickerResult done = new TickerResult(ticker, rec.map(r -> r.getAction() == null ? null : r.getAction().label()).orElse(null),
					rec.map(Recommendation::getConvictionScore).orElse(null), stopsBefore.size(), exited, tightened, newLegs);
			results.add(done);
			List<TickerResult> soFar = List.copyOf(results);
			update(st -> new JobStatus(st.id(), st.step(), st.total(), soFar.size(), ticker, "Applied to its open trades", soFar,
					null, null, st.startedAt(), null, null));
		}
		update(st -> new JobStatus(st.id(), Step.SUMMARIZING, st.total(), st.done(), null, "Summarizing", st.results(), null, null,
				st.startedAt(), null, null));
		ReviewResult out = new ReviewResult(false, results.size(), results.stream().mapToInt(r -> r.exited().size()).sum(),
				results.stream().mapToInt(TickerResult::stopsTightened).sum(), results);
		log.info("Open-trade review: {} ticker(s), {} leg(s) exited, {} stop(s) tightened", out.tickers(), out.exited(),
				out.stopsTightened());
		List<TickerResult> finalResults = List.copyOf(results);
		update(st -> new JobStatus(st.id(), Step.DONE, st.total(), st.done(), null, null, finalResults, out.exited(),
				out.stopsTightened(), st.startedAt(), java.time.Instant.now(), null));
		return out;
	}

	/** Plain words for an exit reason, as the UI shows them. */
	static String reasonLabel(String reason) {
		return switch (reason == null ? "" : reason) {
			case "THESIS_DECAY" -> "call reversed";
			case "THESIS_FLIP" -> "Agent 11 turned against it";
			case "STOP" -> "stop hit";
			case "TRAILING_STOP" -> "trailing stop hit";
			case "TAKE_PROFIT" -> "took profit";
			case "HORIZON" -> "reached its horizon";
			default -> reason == null ? "closed" : reason.toLowerCase().replace('_', ' ');
		};
	}

	private List<SimulatedTrade> openOn(String ticker) {
		return trades.findByTickerAndStatus(ticker, SimulatedTrade.Status.OPEN);
	}
}
