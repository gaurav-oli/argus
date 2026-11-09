package com.argus.grading;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The live/shadow switch for every gated phase of the strategy rebuild (docs/strategy-rebuild-plan.md).
 *
 * <p>Every phase ships in SHADOW: it runs, measures and records, but changes nothing real. It flips to LIVE only when
 * its pre-registered pass test passes (each phase's evaluator calls {@link #record}), and drops to REVERTED when its
 * automatic switch-off trigger fires. {@code argus.rebuild.kill-switch=true} forces every gate to read as not live.
 * A phase that depends on another (Phases 3–4 on Phase 1) can't go live before it.
 */
@Service
public class StrategyGateService {

	private static final Logger log = LoggerFactory.getLogger(StrategyGateService.class);

	public static final String PHASE1_WEIGHTS = "phase1-report-card-weights";
	public static final String PHASE3_STRETCH = "phase3-stretch-rule";
	public static final String PHASE4_DEFENSE = "phase4-defense";
	public static final String PHASE4_MACRO_SHORT = "phase4-macro-short";
	public static final String PHASE5_CORE = "phase5-core-bucket";

	public enum Status {
		/** Running and measuring; changes nothing real. */
		SHADOW,
		/** Passed its pre-registered test; in effect. */
		LIVE,
		/** Was live; its automatic switch-off fired. Back on the old behavior until reviewed. */
		REVERTED,
		/** Its pass test failed; stays off. */
		FAILED
	}

	private static final Duration CACHE_TTL = Duration.ofSeconds(60);

	private final JdbcTemplate jdbc;
	private final boolean killSwitch;
	private final Map<String, Status> cache = new ConcurrentHashMap<>();
	private volatile Instant cachedAt = Instant.EPOCH;

	public StrategyGateService(JdbcTemplate jdbc, @Value("${argus.rebuild.kill-switch:false}") boolean killSwitch) {
		this.jdbc = jdbc;
		this.killSwitch = killSwitch;
	}

	public Status status(String gate) {
		if (Instant.now().isAfter(cachedAt.plus(CACHE_TTL))) {
			reload();
		}
		return cache.getOrDefault(gate, Status.SHADOW);
	}

	/** True only when this gate passed and nothing forces it off. */
	public boolean live(String gate) {
		return !killSwitch && status(gate) == Status.LIVE;
	}

	/**
	 * Record an evaluation. {@code details} is the evidence (JSON object text) — kept with the decision so anyone can
	 * see why a phase went live or stayed off.
	 */
	public void record(String gate, Status status, String detailsJson, String note) {
		Status before = status(gate);
		jdbc.update("""
				insert into strategy_gate (name, status, details, evaluated_at, switched_at, note)
				values (?, ?, ?::jsonb, now(), now(), ?)
				on conflict (name) do update set status = excluded.status, details = excluded.details,
				    evaluated_at = now(), note = excluded.note,
				    switched_at = case when strategy_gate.status <> excluded.status then now() else strategy_gate.switched_at end""",
				gate, status.name(), detailsJson == null ? "{}" : detailsJson, note);
		if (before != status) {
			log.warn("Strategy gate {}: {} → {} ({})", gate, before, status, note);
		}
		reload();
	}

	public java.util.Optional<Instant> switchedAt(String gate) {
		List<java.sql.Timestamp> ts = jdbc.queryForList("select switched_at from strategy_gate where name = ?",
				java.sql.Timestamp.class, gate);
		return ts.isEmpty() || ts.get(0) == null ? java.util.Optional.empty() : java.util.Optional.of(ts.get(0).toInstant());
	}

	public List<Map<String, Object>> all() {
		return jdbc.queryForList(
				"select name, status, details::text as details, evaluated_at, switched_at, note from strategy_gate order by name");
	}

	public boolean killSwitch() {
		return killSwitch;
	}

	private void reload() {
		Map<String, Status> fresh = new ConcurrentHashMap<>();
		jdbc.query("select name, status from strategy_gate", rs -> {
			try {
				fresh.put(rs.getString(1), Status.valueOf(rs.getString(2)));
			}
			catch (IllegalArgumentException ex) {
				log.warn("Unknown gate status {} for {}", rs.getString(2), rs.getString(1));
			}
		});
		cache.clear();
		cache.putAll(fresh);
		cachedAt = Instant.now();
	}
}
