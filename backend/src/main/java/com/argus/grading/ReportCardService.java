package com.argus.grading;

import com.argus.grading.ReportCardMath.Card;
import com.argus.grading.ReportCardMath.Obs;
import com.argus.grading.ReportCardMath.Situation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns graded predictions into each agent's report card and the weight it earns (strategy rebuild, Phase 1).
 * The math lives in {@link ReportCardMath}; this class only loads, saves and caches.
 */
@Service
public class ReportCardService {

	private static final Logger log = LoggerFactory.getLogger(ReportCardService.class);

	/**
	 * One graded result counts at most this many points either way. A single sub-dollar spike (RETO, +402% in ten
	 * days) once decided most of Chart Reader's grade on its own; capped, it still counts as a big miss.
	 */
	static final double EXCESS_CAP = 30;

	/** Stocks under this price at entry aren't graded — Argus doesn't buy them, and their swings swamp the rest. */
	static final double MIN_ENTRY_PRICE = 5;

	/**
	 * Agents whose signal changed meaning on a date: their live card restarts there, since older grades describe
	 * the old signal. (News and Chart Reader now score against each stock's own normal, 2026-11-10.) The Phase 1
	 * backfill fit ({@link #fittedUntil}) still uses everything — it judges the calls that were actually made.
	 */
	static final Map<String, LocalDate> RESTARTED = Map.of(
			"agent-1-news", LocalDate.of(2026, 11, 10),
			"agent-10-technical", LocalDate.of(2026, 11, 10));

	private final JdbcTemplate jdbc;
	private final ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
	private final Map<String, Weight> cache = new ConcurrentHashMap<>();

	/** What the live path needs per agent: the weights and whether to trust the stated strength. */
	public record Weight(double multiplier, Double pinned, Map<String, Double> situational,
			Map<String, Integer> situationClusters, Boolean strengthUseful, double meanStrength, String state) {
	}

	public ReportCardService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Recompute and persist every agent's card from all graded predictions. */
	public List<Card> recompute() {
		Map<String, List<Obs>> byAgent = load(null);
		Map<String, double[]> current = new HashMap<>();
		Map<String, Map<String, Double>> currentSits = new HashMap<>();
		Map<String, Double> pinned = new HashMap<>();
		jdbc.query("select agent, multiplier, pinned_multiplier, situational::text from agent_report_card", rs -> {
			current.put(rs.getString(1), new double[] {rs.getDouble(2)});
			double p = rs.getDouble(3);
			if (!rs.wasNull()) pinned.put(rs.getString(1), p);
			currentSits.put(rs.getString(1), situationMultipliers(rs.getString(4)));
		});
		List<Card> cards = new ArrayList<>();
		for (Map.Entry<String, List<Obs>> e : byAgent.entrySet()) {
			LocalDate restart = RESTARTED.get(e.getKey());
			if (restart != null) {
				e.setValue(e.getValue().stream().filter(o -> !o.date().isBefore(restart)).toList());
			}
			double mult = current.containsKey(e.getKey()) ? current.get(e.getKey())[0] : 1.0;
			Card c = ReportCardMath.card(e.getKey(), e.getValue(), mult, currentSits.getOrDefault(e.getKey(), Map.of()));
			save(c);
			cards.add(c);
		}
		loadCache();
		log.info("Report cards: {} agent(s) graded — {}", cards.size(), cards.stream()
				.map(c -> c.agent() + "=" + c.state() + "×" + c.multiplier()).toList());
		return cards;
	}

	/**
	 * Cards fitted only on predictions made on or before {@code until} — the Phase 1 backfill check fits here and
	 * scores the weights on later calls they never saw. Nothing is persisted.
	 */
	public Map<String, Card> fittedUntil(LocalDate until) {
		Map<String, Card> out = new LinkedHashMap<>();
		load(until).forEach((agent, obs) -> out.put(agent, ReportCardMath.card(agent, obs, 1.0, Map.of())));
		return out;
	}

	/** Graded predictions per agent, each at the agent's own timeframe; {@code until} null = all of them. */
	Map<String, List<Obs>> load(LocalDate until) {
		Map<String, List<Obs>> out = new LinkedHashMap<>();
		jdbc.query("""
				select agent, call_date, sector, strength, direction, regime, earnings_week,
				       excess_5, excess_10, excess_20, excess_60
				from signal_grade
				where direction in ('BULLISH', 'BEARISH') and (?::date is null or call_date <= ?::date)
				  and (entry_close is null or entry_close >= ?)
				order by agent, call_date""", rs -> {
			String agent = rs.getString("agent");
			int h = ReportCardMath.horizonFor(agent);
			double excess = Math.max(-EXCESS_CAP, Math.min(EXCESS_CAP, rs.getDouble("excess_" + h)));
			if (rs.wasNull()) {
				out.computeIfAbsent(agent, k -> new ArrayList<>()); // still listed, just nothing graded yet
				return;
			}
			int sign = "BULLISH".equals(rs.getString("direction")) ? 1 : -1;
			out.computeIfAbsent(agent, k -> new ArrayList<>()).add(new Obs(rs.getDate("call_date").toLocalDate(),
					rs.getString("sector"), rs.getDouble("strength"), sign, excess, rs.getString("regime"),
					rs.getBoolean("earnings_week")));
		}, until, until, MIN_ENTRY_PRICE);
		return out;
	}

	private void save(Card c) {
		ObjectNode sits = json.createObjectNode();
		for (Map.Entry<String, Situation> e : c.situations().entrySet()) {
			ObjectNode n = sits.putObject(e.getKey());
			n.put("clusters", e.getValue().grade().clusters());
			n.put("calls", e.getValue().grade().calls());
			n.put("edge", round(e.getValue().grade().edge()));
			n.put("hitRate", round(e.getValue().grade().hitRate()));
			n.put("target", round(e.getValue().target()));
			n.put("multiplier", e.getValue().multiplier());
		}
		jdbc.update("""
				insert into agent_report_card (agent, horizon_days, calls, clusters, edge_pct, hit_rate, strong_edge,
				    weak_edge, strength_useful, mean_strength, situational, state, target_multiplier, multiplier, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())
				on conflict (agent) do update set horizon_days = excluded.horizon_days, calls = excluded.calls,
				    clusters = excluded.clusters, edge_pct = excluded.edge_pct, hit_rate = excluded.hit_rate,
				    strong_edge = excluded.strong_edge, weak_edge = excluded.weak_edge,
				    strength_useful = excluded.strength_useful, mean_strength = excluded.mean_strength,
				    situational = excluded.situational, state = excluded.state,
				    target_multiplier = excluded.target_multiplier, multiplier = excluded.multiplier, updated_at = now()""",
				c.agent(), c.horizonDays(), c.overall().calls(), c.overall().clusters(),
				c.overall().clusters() == 0 ? null : round(c.overall().edge()),
				c.overall().clusters() == 0 ? null : round(c.overall().hitRate()),
				c.strongEdge() == null ? null : round(c.strongEdge()), c.weakEdge() == null ? null : round(c.weakEdge()),
				c.strengthUseful(), round(c.meanStrength()), sits.toString(), c.state().name(), round(c.target()),
				c.multiplier());
	}

	/** Reload the live-path cache from the table. */
	public void loadCache() {
		Map<String, Weight> fresh = new HashMap<>();
		jdbc.query("""
				select agent, multiplier, pinned_multiplier, situational::text, strength_useful, mean_strength, state
				from agent_report_card""", rs -> {
			double p = rs.getDouble("pinned_multiplier");
			Double pinned = rs.wasNull() ? null : p;
			boolean su = rs.getBoolean("strength_useful");
			Boolean useful = rs.wasNull() ? null : su;
			String sitJson = rs.getString("situational");
			fresh.put(rs.getString("agent"), new Weight(rs.getDouble("multiplier"), pinned, situationMultipliers(sitJson),
					situationClusters(sitJson), useful, rs.getDouble("mean_strength"), rs.getString("state")));
		});
		cache.clear();
		cache.putAll(fresh);
	}

	public java.util.Optional<Weight> weight(String agent) {
		if (cache.isEmpty()) {
			loadCache();
		}
		return java.util.Optional.ofNullable(cache.get(agent));
	}

	/** Manually pin (or with null, unpin) an agent's weight. */
	public void pin(String agent, Double multiplier) {
		if (multiplier != null && (multiplier < -1 || multiplier > ReportCardMath.MAX_MULTIPLIER)) {
			throw new IllegalArgumentException("pinned multiplier must be between -1 and " + ReportCardMath.MAX_MULTIPLIER);
		}
		jdbc.update("""
				insert into agent_report_card (agent, horizon_days, pinned_multiplier) values (?, ?, ?)
				on conflict (agent) do update set pinned_multiplier = excluded.pinned_multiplier, updated_at = now()""",
				agent, ReportCardMath.horizonFor(agent), multiplier);
		loadCache();
	}

	public List<Map<String, Object>> cards() {
		return jdbc.queryForList("""
				select agent, horizon_days, calls, clusters, edge_pct, hit_rate, strong_edge, weak_edge, strength_useful,
				       situational::text as situational, state, target_multiplier, multiplier, pinned_multiplier, updated_at
				from agent_report_card order by agent""");
	}

	private Map<String, Double> situationMultipliers(String sitJson) {
		Map<String, Double> out = new HashMap<>();
		if (sitJson == null) return out;
		try {
			json.readTree(sitJson).properties().forEach(e -> out.put(e.getKey(), e.getValue().path("multiplier").asDouble(1.0)));
		}
		catch (Exception ex) {
			log.debug("Unreadable situational json: {}", ex.getMessage());
		}
		return out;
	}

	private Map<String, Integer> situationClusters(String sitJson) {
		Map<String, Integer> out = new HashMap<>();
		if (sitJson == null) return out;
		try {
			json.readTree(sitJson).properties().forEach(e -> out.put(e.getKey(), e.getValue().path("clusters").asInt(0)));
		}
		catch (Exception ex) {
			log.debug("Unreadable situational json: {}", ex.getMessage());
		}
		return out;
	}

	private static double round(double v) {
		return Math.round(v * 10000) / 10000.0;
	}
}
