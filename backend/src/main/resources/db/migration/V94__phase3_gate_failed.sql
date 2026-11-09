-- Strategy rebuild, Phase 3: both rules failed their pre-registered backtest (research/phase3_stretch_backtest.py,
-- run 2026-11-09 on the 10-year universe; fit before 2023, hold-out 2023 onward, cutoffs 2/3/4 ATR only).
--   Buy side ("wait for pullback"): chosen k=3 on fit; hold-out gain -0.01 pts on 1,581 clusters (needed >= +1.0),
--     and expired calls (+1.11) beat filled ones (+0.15).
--   Mirror (no AVOID on stocks stretched down): chosen k=2; hold-out gain -0.02 pts on 1,501 clusters.
-- Agent 5's own 28 graded BUY calls since 2026-09-25: only 1-2 were stretched at k=2-3; their losses came from
-- elsewhere. Neither rule goes live; stretch is recorded as a feature token for the Trade Learner instead.
insert into strategy_gate (name, status, details, evaluated_at, switched_at, note)
values ('phase3-stretch-rule', 'FAILED',
        '{"buySide":{"cutoff":3,"holdoutGainPts":-0.01,"clusters":1581,"filledMean":0.15,"expiredMean":1.11},"mirror":{"cutoff":2,"holdoutGainPts":-0.02,"clusters":1501},"agent5BuyCallsGraded":28,"agent5Stretched":2}'::jsonb,
        now(), now(), 'backtest failed the pre-registered test — not built as a live rule; stretch recorded as a learner feature')
on conflict (name) do nothing;
