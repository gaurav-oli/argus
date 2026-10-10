-- S-B4: the pattern library. Every paper entry is fingerprinted, and before it opens, the Investor looks up
-- similar past closed trades and may skip, size down, or tighten the stop.
--
-- Fingerprint schema: a JSON array of feature tokens (learning/FeatureTokens), the same vocabulary the
-- recommendation stores in recommendations.features, e.g.
--   ["conv=70-79","dir=BULLISH","has=NEWS","lead=DEEP","price=50-200","regime=RISK_ON","sector=TECH",
--    "trend=UPTREND","val=FAIR","vol=mid"]
-- Keys: dir (direction), sector, regime (market regime), trend and vol (Agent 10's chart), conv (conviction
-- band), has/lead (which evidence groups backed the call and which led), guidance/val (fundamentals),
-- price (price band), deep (Deep Analyst verdict) and hold (horizon).

alter table simulated_trades add column setup_fingerprint text;
alter table simulated_trades add column pattern_advice text;

create table pattern_check (
    id                 bigint generated always as identity primary key,
    recommendation_id  bigint,
    ticker             varchar(20)  not null,
    direction          varchar(10)  not null,
    checked_at         timestamptz  not null default now(),
    fingerprint        text         not null,
    matches            int          not null,
    wins               int          not null,
    win_rate_pct       int,
    avg_return_pct     numeric(10, 4),
    stop_out_pct       int,
    -- PROCEED | SIZE_DOWN | TIGHTEN_STOP | SKIP | NO_PATTERN
    action             varchar(20)  not null,
    size_multiplier    numeric(6, 3) not null,
    stop_keep          numeric(6, 3) not null,
    pattern            text,
    note               text         not null,
    similar_trade_ids  text
);

create index pattern_check_checked_at_idx on pattern_check (checked_at desc);
create index pattern_check_recommendation_idx on pattern_check (recommendation_id);
create index pattern_check_ticker_idx on pattern_check (ticker);
