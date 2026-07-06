-- Epic 8 (FR-16, follow-up): the on-demand "market pulse" — a short local-model summary of the
-- market-impacting news captured so far. A single row (the latest pulse) the dashboard refreshes on
-- demand. `latest_article_at` is the newest published_at covered by the summary, so a refresh can
-- tell there's "nothing major since we last checked" without regenerating.
CREATE TABLE market_pulse (
    id                smallint     PRIMARY KEY DEFAULT 1,
    summary           text         NOT NULL,
    article_count     int          NOT NULL DEFAULT 0,
    latest_article_at timestamptz,
    generated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT market_pulse_singleton CHECK (id = 1)
);
