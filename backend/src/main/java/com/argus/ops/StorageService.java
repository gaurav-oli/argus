package com.argus.ops;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-agent data-storage inventory for the Operations view: how many rows each agent has accumulated
 * and how much disk it occupies, and in which Postgres table it lives. Row counts are exact
 * ({@code count(*)}), sizes come from {@code pg_total_relation_size} (table + indexes + TOAST). Tables
 * are grouped by the agent/domain that writes them via a fixed whitelist; any base table not in the
 * map still shows up under "Other", so nothing is hidden.
 */
@Service
public class StorageService {

	/** A table owned by an agent group, with a plain-language note on what it holds. */
	private record TableSpec(String table, String label, String stores) {
	}

	/** An agent/domain group and the tables it owns. Order here is the display order. */
	private record GroupSpec(String key, String name, String description, List<TableSpec> tables) {
	}

	// The map of agent → tables. Keep in sync with the schema; unmapped tables surface under "Other".
	private static final List<GroupSpec> GROUPS = List.of(
			new GroupSpec("news", "Agent 1 · News Intelligence", "Market news it fetched and analyzed", List.of(
					new TableSpec("news_articles", "News articles", "Raw headlines + sentiment analysis"),
					new TableSpec("news_card", "News reader queue", "Curated cards with Gemma summaries"))),
			new GroupSpec("social", "Agent 2 · Social Sentiment", "Social posts it scanned", List.of(
					new TableSpec("social_posts", "Social posts", "StockTwits/Reddit posts by ticker"))),
			new GroupSpec("web", "Agent 3 · Web Mentions", "Web/GDELT mentions it tracked", List.of(
					new TableSpec("web_mentions", "Web mentions", "GDELT/news web mentions by ticker"))),
			new GroupSpec("sec", "Agent 4 · SEC Filings", "Regulatory filings it ingested", List.of(
					new TableSpec("sec_filings", "SEC filings", "EDGAR filings by ticker"))),
			new GroupSpec("recs", "Agent 5 · Analyst & Investor", "Recommendations + the learning corpus", List.of(
					new TableSpec("recommendations", "Recommendations", "Every call the Analyst made"),
					new TableSpec("recommendation_signals", "Rec signals", "Per-signal breakdown behind each call"),
					new TableSpec("persona_verdicts", "Persona verdicts", "Bull/bear persona takes"),
					new TableSpec("trade_decisions", "Trade decisions", "Your accept/decline on each call"),
					new TableSpec("simulated_trades", "Simulated trades", "Investor's paper positions"),
					new TableSpec("paper_trades", "Paper trade outcomes", "Closed win/loss — feeds tuning"),
					new TableSpec("agent_reliability", "Agent reliability", "Learned per-agent weight multipliers"),
					new TableSpec("probability_calibration", "Probability calibration", "Learned probability curve"))),
			new GroupSpec("cost", "Agent 6 · Cost Governor", "Paid-API spend ledger", List.of(
					new TableSpec("cost_events", "Cost events", "Each paid Haiku call's cost"))),
			new GroupSpec("calendar", "Agent 7 · Economic Calendar", "Economic events it watches", List.of(
					new TableSpec("calendar_events", "Calendar events", "Upcoming/past economic events"),
					new TableSpec("calendar_event_alerts", "Calendar alerts", "Alerts raised for events"))),
			new GroupSpec("stranger", "Stranger Danger", "Unusual-activity + source trust", List.of(
					new TableSpec("stranger_alerts", "Stranger alerts", "Unfamiliar-ticker activity spikes"),
					new TableSpec("source_credibility", "Source credibility", "Learned trust per source"))),
			new GroupSpec("portfolio", "Your Portfolio", "Your holdings and their history", List.of(
					new TableSpec("positions", "Positions", "Your holdings"),
					new TableSpec("position_lots", "Position lots", "Tax lots per holding"),
					new TableSpec("corporate_actions", "Corporate actions", "Splits/dividends"),
					new TableSpec("cash_balances", "Cash balances", "Cash by account"),
					new TableSpec("portfolio_value_history", "Value history", "Daily portfolio value snapshots"),
					new TableSpec("position_audit", "Position audit", "Change history per holding"),
					new TableSpec("portfolio_imports", "Imports", "Statement import batches"))),
			new GroupSpec("briefing", "Briefings", "Morning briefing + market pulse", List.of(
					new TableSpec("briefings", "Briefings", "Daily morning briefings"),
					new TableSpec("market_pulse", "Market pulse", "Latest market-pulse summary"))),
			new GroupSpec("system", "System", "Auth, settings, health", List.of(
					new TableSpec("webauthn_credential", "Passkeys", "Your WebAuthn credentials"),
					new TableSpec("app_settings", "Settings", "App preferences"),
					new TableSpec("health_score", "Health score", "Portfolio health snapshot"))));

	public record TableStorage(String table, String label, String stores, long rows, long bytes) {
	}

	public record AgentStorage(String key, String name, String description, long rows, long bytes,
			List<TableStorage> tables) {
	}

	public record StorageView(String database, long totalRows, long totalBytes, Instant generatedAt,
			List<AgentStorage> agents) {
	}

	private final JdbcTemplate jdbc;

	public StorageService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public StorageView snapshot() {
		Set<String> existing = existingTables();
		Map<String, Long> sizes = tableSizes(existing);
		Map<String, Long> counts = tableCounts(existing);
		String db = jdbc.queryForObject("select current_database()", String.class);

		List<AgentStorage> agents = new ArrayList<>();
		Set<String> mapped = GROUPS.stream()
				.flatMap(g -> g.tables().stream()).map(TableSpec::table).collect(Collectors.toSet());

		for (GroupSpec g : GROUPS) {
			List<TableStorage> tables = new ArrayList<>();
			for (TableSpec t : g.tables()) {
				if (!existing.contains(t.table())) {
					continue; // schema drift — skip a table that isn't there
				}
				tables.add(new TableStorage(t.table(), t.label(), t.stores(),
						counts.getOrDefault(t.table(), 0L), sizes.getOrDefault(t.table(), 0L)));
			}
			if (!tables.isEmpty()) {
				agents.add(group(g.key(), g.name(), g.description(), tables));
			}
		}

		// Anything present but not mapped — surface it so storage is never under-reported.
		List<TableStorage> other = existing.stream()
				.filter(name -> !mapped.contains(name))
				.map(name -> new TableStorage(name, name, "Unmapped table",
						counts.getOrDefault(name, 0L), sizes.getOrDefault(name, 0L)))
				.sorted((a, b) -> Long.compare(b.bytes(), a.bytes()))
				.toList();
		if (!other.isEmpty()) {
			agents.add(group("other", "Other", "Tables not owned by a specific agent", other));
		}

		long totalRows = agents.stream().mapToLong(AgentStorage::rows).sum();
		long totalBytes = agents.stream().mapToLong(AgentStorage::bytes).sum();
		return new StorageView(db, totalRows, totalBytes, Instant.now(), agents);
	}

	private static AgentStorage group(String key, String name, String description, List<TableStorage> tables) {
		long rows = tables.stream().mapToLong(TableStorage::rows).sum();
		long bytes = tables.stream().mapToLong(TableStorage::bytes).sum();
		List<TableStorage> sorted = tables.stream()
				.sorted((a, b) -> Long.compare(b.bytes(), a.bytes())).toList();
		return new AgentStorage(key, name, description, rows, bytes, sorted);
	}

	private Set<String> existingTables() {
		return Set.copyOf(jdbc.queryForList(
				"select table_name from information_schema.tables "
						+ "where table_schema = 'public' and table_type = 'BASE TABLE'",
				String.class));
	}

	private Map<String, Long> tableSizes(Set<String> tables) {
		Map<String, Long> sizes = new LinkedHashMap<>();
		jdbc.query(
				"select table_name, pg_total_relation_size(quote_ident(table_name)) as bytes "
						+ "from information_schema.tables "
						+ "where table_schema = 'public' and table_type = 'BASE TABLE'",
				rs -> {
					sizes.put(rs.getString("table_name"), rs.getLong("bytes"));
				});
		return sizes;
	}

	/**
	 * Exact row counts in a single round trip. Table names come only from the Postgres catalog
	 * (information_schema base tables) — never user input — so the dynamic SQL is safe.
	 */
	private Map<String, Long> tableCounts(Set<String> tables) {
		if (tables.isEmpty()) {
			return Map.of();
		}
		String union = tables.stream()
				.map(t -> "select '" + t + "' as t, count(*) as c from " + t)
				.collect(Collectors.joining(" union all "));
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbc.query(union, rs -> {
			counts.put(rs.getString("t"), rs.getLong("c"));
		});
		return counts;
	}
}
