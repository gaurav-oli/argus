package com.argus.grading;

import com.argus.grading.PostmortemBuckets.Bucket;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Writes a numeric post-mortem for every closed real and shadow trade (strategy rebuild, Phase 2). Nothing here is an
 * opinion: the bucket comes from {@link PostmortemBuckets} on the trade's result vs SPY, what holding to the planned
 * date would have done, whether the agents it relied on were right (from the same grader as the report cards), and
 * whether a shock hit it. A post-mortem stays provisional until every window it depends on has closed, then is final.
 */
@Service
@Order(20)
public class PostmortemService implements NightlyStep {

	private static final Logger log = LoggerFactory.getLogger(PostmortemService.class);
	private static final ZoneId NY = ZoneId.of("America/New_York");
	/** A SPY day this bad (or good, against a short) counts as a market-shock day. */
	static final double MARKET_SHOCK_PCT = 2.0;
	/** A gap this many times the stock's normal daily range counts as a shock. */
	static final double GAP_ATRS = 2.0;
	static final LocalDate CURRENT_SYSTEM_SINCE = LocalDate.of(2026, 9, 25);

	private final JdbcTemplate jdbc;

	public PostmortemService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public String name() {
		return "post-mortems";
	}

	@Override
	public void run() {
		int n = 0;
		for (Map<String, Object> t : pending()) {
			try {
				write(t);
				n++;
			}
			catch (RuntimeException ex) {
				log.warn("Post-mortem for {} trade {} failed: {}", t.get("book"), t.get("id"), ex.getMessage());
			}
		}
		log.info("Post-mortems: {} written or refreshed", n);
	}

	/** Closed trades whose post-mortem is missing or not yet final. */
	List<Map<String, Object>> pending() {
		return jdbc.queryForList("""
				select 'REAL' as book, t.id, t.recommendation_id, t.ticker, t.direction, t.entry_at, t.closed_at,
				       t.exit_reason, t.excess_return_pct::float8 as excess, t.horizon_days as hold_days,
				       t.cost_pct::float8 as cost_pct, null::float8 as atr_pct, r.sector, r.created_at as called_at
				from simulated_trades t left join recommendations r on r.id = t.recommendation_id
				where t.status = 'CLOSED' and t.parent_trade_id is null and t.excess_return_pct is not null
				  and t.entry_at >= ?::date
				  and not exists (select 1 from trade_postmortem p where p.book = 'REAL' and p.trade_id = t.id and p.final)
				union all
				select 'SHADOW', t.id, t.recommendation_id, t.ticker, t.direction, t.entry_at,
				       (t.exit_date + time '16:00') at time zone 'America/New_York',
				       t.exit_reason, t.excess_return_pct, t.hold_days, t.cost_pct, t.atr_pct, t.sector, r.created_at
				from shadow_trades t left join recommendations r on r.id = t.recommendation_id
				where t.status = 'CLOSED' and t.excess_return_pct is not null
				  and not exists (select 1 from trade_postmortem p where p.book = 'SHADOW' and p.trade_id = t.id and p.final)""",
				CURRENT_SYSTEM_SINCE);
	}

	void write(Map<String, Object> t) {
		String book = (String) t.get("book");
		long id = ((Number) t.get("id")).longValue();
		String ticker = (String) t.get("ticker");
		String direction = (String) t.get("direction");
		int sign = "BULLISH".equals(direction) ? 1 : -1;
		LocalDate entryDate = ((Timestamp) t.get("entry_at")).toInstant().atZone(NY).toLocalDate();
		LocalDate closedDate = t.get("closed_at") == null ? entryDate : ((Timestamp) t.get("closed_at")).toInstant().atZone(NY).toLocalDate();
		double excess = ((Number) t.get("excess")).doubleValue();
		int holdDays = t.get("hold_days") == null ? 30 : ((Number) t.get("hold_days")).intValue();
		double cost = t.get("cost_pct") == null ? 0 : ((Number) t.get("cost_pct")).doubleValue();
		Long recId = t.get("recommendation_id") == null ? null : ((Number) t.get("recommendation_id")).longValue();
		LocalDate callDate = t.get("called_at") == null ? entryDate : ((Timestamp) t.get("called_at")).toInstant().atZone(NY).toLocalDate();

		Double holdExcess = holdExcess(ticker, entryDate, holdDays, sign, cost);

		// What the agents it relied on said, and whether each was right on its own timeframe.
		Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
		double rightW = 0;
		double wrongW = 0;
		boolean allGraded = true;
		List<String> relied = new ArrayList<>();
		if (recId != null) {
			for (Map<String, Object> s : jdbc.queryForList("""
					select s.agent, s.weight::float8 as weight, g.excess_5, g.excess_10, g.excess_20, g.excess_60
					from recommendation_signals s
					left join signal_grade g on g.agent = s.agent and g.ticker = ? and g.call_date = ?
					where s.recommendation_id = ? and s.direction = ? and s.weight > 0""", ticker, callDate, recId, direction)) {
				String agent = (String) s.get("agent");
				relied.add(agent);
				int h = ReportCardMath.horizonFor(agent);
				Object ex = s.get("excess_" + h);
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("strength", s.get("weight"));
				c.put("horizonDays", h);
				if (ex == null) {
					allGraded = false;
					c.put("graded", false);
				}
				else {
					double e = ((Number) ex).doubleValue();
					boolean right = sign * e > 0;
					c.put("excess", Math.round(e * 100) / 100.0);
					c.put("right", right);
					double w = ((Number) s.get("weight")).doubleValue();
					if (right) rightW += w;
					else wrongW += w;
				}
				checks.put(agent, c);
			}
		}
		Boolean reliedRight = rightW + wrongW == 0 ? null : rightW >= wrongW;

		String shockReason = shock(ticker, entryDate, closedDate, sign, t.get("atr_pct") == null ? null
				: ((Number) t.get("atr_pct")).doubleValue());
		Bucket bucket = PostmortemBuckets.classify(excess, holdExcess, reliedRight, shockReason != null);
		boolean fin = holdExcess != null && allGraded;

		jdbc.update("""
				insert into trade_postmortem (book, trade_id, recommendation_id, ticker, direction, sector, entry_date, closed_date,
				    exit_reason, excess_pct, hold_excess_pct, bucket, relied_on, relied_right, shock, shock_reason, agent_checks,
				    final, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
				on conflict (book, trade_id) do update set hold_excess_pct = excluded.hold_excess_pct, bucket = excluded.bucket,
				    relied_on = excluded.relied_on, relied_right = excluded.relied_right, shock = excluded.shock,
				    shock_reason = excluded.shock_reason, agent_checks = excluded.agent_checks, final = excluded.final,
				    updated_at = now()""",
				book, id, recId, ticker, direction, t.get("sector"), entryDate, closedDate, t.get("exit_reason"), excess,
				holdExcess, bucket.name(), String.join(",", relied), reliedRight, shockReason != null, shockReason,
				toJson(checks), fin);
	}

	/** Return vs SPY had the trade been held exactly {@code holdDays} SPY trading days from entry; null until then. */
	Double holdExcess(String ticker, LocalDate entryDate, int holdDays, int sign, double cost) {
		List<Map<String, Object>> r = jdbc.queryForList("""
				with e as (select candle_date, close::float8 as c from price_candles where ticker = ? and candle_date >= ?
				           order by candle_date limit 1),
				     x as (select b.candle_date as d from price_candles b, e where b.ticker = 'SPY' and b.candle_date > e.candle_date
				           order by b.candle_date offset ? limit 1)
				select e.c as entry,
				       (select close::float8 from price_candles where ticker = 'SPY' and candle_date <= e.candle_date
				         order by candle_date desc limit 1) as spy0,
				       (select close::float8 from price_candles, x where ticker = ? and candle_date <= x.d
				         order by candle_date desc limit 1) as exit,
				       (select close::float8 from price_candles, x where ticker = 'SPY' and candle_date = x.d) as spy1
				from e""", ticker, entryDate, Math.max(0, holdDays - 1), ticker);
		if (r.isEmpty()) return null;
		Map<String, Object> m = r.get(0);
		if (m.get("entry") == null || m.get("spy0") == null || m.get("exit") == null || m.get("spy1") == null) return null;
		double stock = ((Number) m.get("exit")).doubleValue() / ((Number) m.get("entry")).doubleValue() - 1;
		double spy = ((Number) m.get("spy1")).doubleValue() / ((Number) m.get("spy0")).doubleValue() - 1;
		return 100 * sign * (stock - spy) - cost;
	}

	/** The first adverse shock while the trade was open, described; null if none. */
	String shock(String ticker, LocalDate from, LocalDate to, int sign, Double atrPct) {
		List<Map<String, Object>> spyDays = jdbc.queryForList("""
				select candle_date, 100 * (close / lag(close) over (order by candle_date) - 1) as ret
				from price_candles where ticker = 'SPY' and candle_date between ?::date - 7 and ?""", from, to);
		for (Map<String, Object> d : spyDays) {
			LocalDate day = ((java.sql.Date) d.get("candle_date")).toLocalDate();
			if (day.isBefore(from) || d.get("ret") == null) continue;
			double ret = ((Number) d.get("ret")).doubleValue();
			if (sign * ret <= -MARKET_SHOCK_PCT) {
				return String.format(Locale.ROOT, "market-shock day %s (SPY %+.1f%%)", day, ret);
			}
		}
		List<Map<String, Object>> bars = jdbc.queryForList("""
				select candle_date, open::float8 as o, high::float8 as h, low::float8 as l, close::float8 as c,
				       lag(close::float8) over (order by candle_date) as prev
				from price_candles where ticker = ? and candle_date between ?::date - 30 and ? order by candle_date""",
				ticker, from, to);
		double atr = atrPct != null && atrPct > 0 ? atrPct : averageRangePct(bars, from);
		for (Map<String, Object> b : bars) {
			LocalDate day = ((java.sql.Date) b.get("candle_date")).toLocalDate();
			if (!day.isAfter(from) || b.get("prev") == null) continue;
			double gap = 100 * (((Number) b.get("o")).doubleValue() / ((Number) b.get("prev")).doubleValue() - 1);
			if (sign * gap <= -GAP_ATRS * atr) {
				return String.format(Locale.ROOT, "gapped %+.1f%% on %s (normal day ≈ %.1f%%)", gap, day, atr);
			}
		}
		return null;
	}

	/** Average daily high-low range in percent over the bars before {@code before}, as a stand-in for ATR %. */
	static double averageRangePct(List<Map<String, Object>> bars, LocalDate before) {
		double sum = 0;
		int n = 0;
		for (Map<String, Object> b : bars) {
			if (!((java.sql.Date) b.get("candle_date")).toLocalDate().isBefore(before)) continue;
			double c = ((Number) b.get("c")).doubleValue();
			if (c <= 0) continue;
			sum += 100 * (((Number) b.get("h")).doubleValue() - ((Number) b.get("l")).doubleValue()) / c;
			n++;
		}
		return n == 0 ? 3.0 : sum / n;
	}

	/** Bucket counts per book over the last {@code days}, with sample sizes (Agents page). */
	public List<Map<String, Object>> summary(int days) {
		return jdbc.queryForList("""
				select book, bucket, count(*) as trades, count(*) filter (where final) as final_count,
				       avg(excess_pct) as avg_excess
				from trade_postmortem where closed_date >= current_date - ? group by book, bucket order by book, bucket""", days);
	}

	/** The trades behind one bucket — what a click on a bucket count opens. */
	public List<Map<String, Object>> trades(String book, String bucket, int days) {
		return jdbc.queryForList("""
				select book, trade_id, ticker, direction, entry_date, closed_date, exit_reason, excess_pct, hold_excess_pct,
				       bucket, relied_on, relied_right, shock_reason, agent_checks::text as agent_checks, final
				from trade_postmortem where book = ? and bucket = ? and closed_date >= current_date - ?
				order by closed_date desc""", book, bucket, days);
	}

	private static String toJson(Map<String, Map<String, Object>> checks) {
		StringBuilder sb = new StringBuilder("{");
		for (Map.Entry<String, Map<String, Object>> e : checks.entrySet()) {
			if (sb.length() > 1) sb.append(',');
			sb.append('"').append(e.getKey()).append("\":{");
			boolean first = true;
			for (Map.Entry<String, Object> f : e.getValue().entrySet()) {
				if (!first) sb.append(',');
				first = false;
				sb.append('"').append(f.getKey()).append("\":").append(f.getValue());
			}
			sb.append('}');
		}
		return sb.append('}').toString();
	}
}
