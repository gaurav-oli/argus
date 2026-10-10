-- S-B7: the strategy sandbox. A strategy that passes Agent 15's hold-out backtest no longer goes straight into
-- live Agent 5 scoring: it enters SHADOW, makes forward "shadow calls" that are tracked but never weighed, and is
-- PROMOTED only after enough of them beat SPY (rules in strategy/SandboxRules), else KILLED.
--   SHADOW    → making shadow calls; no live effect
--   CANDIDATE → half-way to the sample and above the bar so far; still no live effect
--   PROMOTED  → live: Agent 15 readings include it
--   KILLED    → failed the bar; never live (a later backtest pass does not revive it)
create table strategy_sandbox (
    acronym           text         primary key references academic_strategy (acronym) on delete cascade,
    state             varchar(12)  not null,
    horizon_days      int          not null default 30,
    entered_at        timestamptz  not null default now(),
    state_changed_at  timestamptz  not null default now(),
    reason            text         not null,
    resolved          int          not null default 0,
    hits              int          not null default 0,
    mean_excess_pct   numeric(10, 4)
);

create table strategy_shadow_call (
    id            bigint generated always as identity primary key,
    acronym       text           not null references academic_strategy (acronym) on delete cascade,
    ticker        text           not null,
    direction     varchar(10)    not null,
    view          numeric(6, 4)  not null,
    made_on       date           not null,
    horizon_days  int            not null,
    entry_close   numeric(18, 6) not null,
    spy_entry     numeric(18, 6) not null,
    resolved_on   date,
    exit_close    numeric(18, 6),
    spy_exit      numeric(18, 6),
    excess_pct    numeric(10, 4),
    hit           boolean,
    unique (acronym, ticker, made_on)
);
create index strategy_shadow_call_open_idx on strategy_shadow_call (made_on) where resolved_on is null;

-- Strategies already live keep working: they were validated before the sandbox existed.
insert into strategy_sandbox (acronym, state, reason)
select acronym, 'PROMOTED', 'Grandfathered — validated and live before the sandbox existed (S-B7).'
  from academic_strategy where status = 'ACTIVE';
