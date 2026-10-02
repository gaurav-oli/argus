alter table deep_analysis
    add column verdict_model varchar(16);

-- Append-only, same shape as deep_scorecard_snapshot but segmented by which model actually produced the
-- verdict ("HAIKU" vs "LOCAL") — the measured answer to whether paying for Haiku's verdict call beats
-- Gemma alone, tracked over time instead of just reasoned about.
create table deep_verdict_model_snapshot (
    id               bigserial primary key,
    model            varchar(16)    not null,
    verdict          varchar(32)    not null,
    horizon_days     integer        not null,
    observations     integer        not null,
    mean_excess_pct  numeric(10, 4) not null,
    hit_rate         numeric(6, 4),
    computed_at      timestamptz    not null default now()
);

create index idx_deep_verdict_model_snapshot_lookup on deep_verdict_model_snapshot (model, verdict, horizon_days, computed_at);
