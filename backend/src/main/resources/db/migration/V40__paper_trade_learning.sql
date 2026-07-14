-- Paper-trade learning fixes (Fable 5 architecture review, 2026-07-14).
--
-- 1. Benchmark-relative wins: a trade now records the SPY price at entry/exit and its
--    direction-adjusted EXCESS return over SPY; `won` is decided on excess return when a benchmark
--    was captured (falls back to absolute when Finnhub was unavailable). This stops the learning
--    loop from crediting agents for market beta ("bullish in a bull market always wins").
-- 2. Thesis re-affirmation: repeated recommendations on an already-open (ticker, direction) no
--    longer open duplicate trades; they increment `reaffirmations` on the open legs instead.
ALTER TABLE simulated_trades
    ADD COLUMN benchmark_entry   numeric(18,6),
    ADD COLUMN benchmark_exit    numeric(18,6),
    ADD COLUMN excess_return_pct numeric(10,4),
    ADD COLUMN reaffirmations    integer NOT NULL DEFAULT 0;

-- 3. Dissent record: each logic-review run now also stores the per-agent dissent stats it showed
--    the LLM reviewer (times an agent disagreed with the final call, and how often the dissenter
--    was right) — agents auditing each other through outcomes.
ALTER TABLE logic_review ADD COLUMN dissent jsonb;

-- ONE-TIME PURGE of the pre-fix paper book (deliberate, reviewed, user-approved 2026-07-14):
-- the 189 open trades were pseudo-replicated (one per 6-hourly re-recommendation — 27 duplicates
-- per ticker on the same thesis) and carry no benchmark entries. Had they closed, adaptive tuning
-- and graduation would have counted them as independent samples and learned noise. Nothing has
-- closed yet and every derived table (paper_trades, agent_reliability, probability_calibration)
-- is empty, so this loses no learning — it resets a poisoned experiment before it reports.
-- These are $100 pretend-money positions, never real portfolio data.
DELETE FROM simulated_trades;
