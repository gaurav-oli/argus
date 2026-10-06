-- V77__agent_runs.sql — when a scheduled agent last completed a run, regardless of whether it found
-- anything new. Agent 7's "last activity" was max(calendar_events.ingested_at), i.e. the last time it
-- found a NEW event, so a quiet calendar week read as "stalled" on the Agents page even though the
-- daily run was healthy. One row per agent, upserted at the end of each run.

create table agent_runs (
    agent_id    text        primary key,
    last_run_at timestamptz not null
);
