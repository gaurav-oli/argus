-- Intelligent recommendations: every recommendation now carries an explicit action (BUY / AVOID / WATCH),
-- a 0-100 conviction score, a recommended holding period (7 / 30 / 90 days), and the reasoning behind
-- it. WATCH is the honest "no clear edge" state the old model lacked — it used to force a 50/50 read
-- into BULLISH and trade it. See RecommendationPolicy.
ALTER TABLE recommendations
    ADD COLUMN action           text,
    ADD COLUMN conviction_score integer,
    ADD COLUMN hold_days        integer,
    ADD COLUMN horizon_label    text,
    ADD COLUMN thesis           text,
    ADD COLUMN reasons          text,      -- newline-separated supporting evidence
    ADD COLUMN caveats          text,      -- newline-separated risks / disagreements
    ADD COLUMN exit_plan        text,
    ADD COLUMN sector           text;

-- Every pre-existing row is a legacy recommendation made without a verdict; none of them met the new
-- bar by construction, so they are marked WATCH and drop off the Intelligence page.
UPDATE recommendations SET action = 'WATCH' WHERE action IS NULL;

CREATE INDEX idx_recommendations_ticker_created ON recommendations (ticker, created_at DESC);
