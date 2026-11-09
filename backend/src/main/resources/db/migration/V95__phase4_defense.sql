-- Strategy rebuild, Phase 4 (docs/strategy-rebuild-plan.md).

-- Defense regime failed its pre-registered backtest (research/phase4_defense_backtest.py, run 2026-11-09; SPY 10y,
-- VIX cutoffs 20/25/30 only, chosen on fit before 2023 = VIX>20): over the full 10 years the worst drop was cut 30%
-- (-34.1% -> -24.0%, bar >= 20%) but 28% of total return was given up (+245% -> +177%, bar <= 25%), 24 episodes.
-- A narrow miss is still a miss: not built as a live rule.
insert into strategy_gate (name, status, details, evaluated_at, switched_at, note)
values ('phase4-defense', 'FAILED',
        '{"vixCutoff":20,"maxDrawdownBase":-0.341,"maxDrawdownDefense":-0.240,"drawdownCut":0.30,"returnBase":2.45,"returnDefense":1.77,"giveUp":0.28,"episodes":24,"holdoutDrawdownCut":0.31,"holdoutGiveUp":0.26}'::jsonb,
        now(), now(), 'backtest gave up 28% of return (bar 25%) — not built as a live rule')
on conflict (name) do nothing;

-- The macro-event short runs in shadow for 12 releases from deployment, then is dropped unless it beats zero after
-- costs. (The 10-year backtest was already negative: FOMC -11.6% over 78, jobs Friday -22.2% over 113.)
create table macro_short_shadow (
    event_date  date primary key,
    kind        text             not null,
    entry_date  date             not null,
    spy_entry   double precision not null,
    spy_exit    double precision not null,
    return_pct  double precision not null,
    created_at  timestamptz      not null default now()
);
