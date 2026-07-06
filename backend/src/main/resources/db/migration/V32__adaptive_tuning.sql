-- Epic 6 Phase B (FR-11 follow-up): the Analyst's learning layer. A scheduled job derives these from
-- the Investor's closed paper trades (V31); they feed back into the next recommendation's signal
-- weights and stated probability. This is a REVERSIBLE layer on top of the pure scoring engine — the
-- base heuristics are untouched, and argus.adaptive-tuning.enabled gates whether it's applied.

-- Per-agent realized reliability → a clamped multiplier on that agent's signal weight.
CREATE TABLE agent_reliability (
    agent             text          PRIMARY KEY,
    sample_size       int           NOT NULL DEFAULT 0,   -- closed trades this agent contributed a directional signal to
    hit_rate          numeric(6,4),                       -- correct / contributed (null until any sample)
    weight_multiplier numeric(6,4)  NOT NULL DEFAULT 1,   -- clamped, shrunk toward 1.0 by sample size
    updated_at        timestamptz   NOT NULL DEFAULT now()
);

-- Per-bin isotonic calibration of the stated directional probability. One row per 10-point bin; the
-- calibrated value is null for bins without enough samples (→ identity: use the stated probability).
CREATE TABLE probability_calibration (
    bin_low        smallint     PRIMARY KEY,               -- 0,10,...,90
    bin_high       smallint     NOT NULL,
    sample_size    int          NOT NULL DEFAULT 0,
    raw_hit_rate   numeric(6,4),                           -- observed win rate in this bin
    calibrated     numeric(6,4),                           -- isotonic (monotone) value, null = insufficient
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
