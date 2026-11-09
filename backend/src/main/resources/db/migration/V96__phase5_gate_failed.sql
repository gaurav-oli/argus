-- Strategy rebuild, Phase 5a: the honest dip-buying backtest failed its pre-registered gate
-- (research/phase5_core_backtest.py, run 2026-11-09). Point-in-time S&P 500 membership rebuilt from Wikipedia's change
-- log; SEC XBRL fundamentals counted only from April 1 after each fiscal year; members that died during a 12-month
-- hold included (buyout -> 0%, bankruptcy/FDIC/shrank/unknown -> -100%; all-zero version -> -100% for all).
-- 10 triggers over 6 downturns (2018-02 .. 2025-03): healthy members down >= 20% vs buying VFV at the same moment:
--   realistic -1.5 pts/yr (bar >= +2), all-zero -8.4 pts/yr (bar >= -2). Only the deepest 2020 tranches beat VFV.
-- Informational: 75% VFV + 25% cash laddered in at -10/-20/-30% returned +283..288% vs +304% fully invested over 10y
-- (worst drop -32% vs -34%).
-- The core-bucket engine is not built.
insert into strategy_gate (name, status, details, evaluated_at, switched_at, note)
values ('phase5-core-bucket', 'FAILED',
        '{"triggers":10,"downturns":6,"realisticExcessPtsPerYear":-1.5,"allZeroExcessPtsPerYear":-8.4,"vfvLadderReturn":2.85,"vfvBuyHoldReturn":3.04}'::jsonb,
        now(), now(), 'survivorship-honest backtest lagged VFV — core-bucket engine not built')
on conflict (name) do nothing;
