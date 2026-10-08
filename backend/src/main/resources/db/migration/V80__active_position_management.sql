-- V80__active_position_management.sql — the paper investor manages open trades instead of only waiting
-- for the horizon (PositionManager / PositionRules): trailing stops, half take-profit at the target,
-- earnings tightening, Agent 5 thesis decay. Plus a hold-to-horizon counterfactual on every early exit,
-- so the Agents page can show whether managing beat waiting.

alter table simulated_trades
    add column initial_stop     numeric(18,6),           -- the stop set at entry (or at backfill); stop_price only tightens from it
    add column high_water       numeric(18,6),           -- best price in the trade's favour since entry (min for a short)
    add column target_price     numeric(18,6),           -- half comes off here (swing calls); null = core hold, trail only
    add column scaled_out       boolean not null default false,
    add column parent_trade_id  bigint references simulated_trades(id),  -- the taken-off half of a scaled-out trade
    add column hold_exit_price  numeric(18,6),           -- for an early exit: the price at the original horizon
    add column hold_return_pct  numeric(10,4);           --   and the direction-adjusted return holding would have made

update simulated_trades set initial_stop = stop_price where stop_price is not null;
