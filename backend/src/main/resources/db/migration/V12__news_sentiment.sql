-- V12__news_sentiment.sql — small-model sentiment + relevance scoring for Agent 1 (Story 4.2, FR-8).
--
-- Added to news_articles by the sentiment stage after ingestion (Story 4.1). All nullable: a row is
-- unscored until the agent processes it (analyzed_at stamps when it did, also guarding re-processing
-- on redelivery). Scores are numeric(4,3): sentiment in [-1.000, 1.000], relevance in [0.000, 1.000].
-- Forward-only.

ALTER TABLE news_articles
    ADD COLUMN sentiment_label  text,
    ADD COLUMN sentiment_score  numeric(4, 3),
    ADD COLUMN relevance_score  numeric(4, 3),
    ADD COLUMN analyzed_at      timestamptz;
