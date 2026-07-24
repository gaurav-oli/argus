-- V46__company_logo.sql — cached ticker -> logo URL lookups (Finnhub /stock/profile2), so the
-- calendar UI can show a company icon per event without an external call in the request path.
-- logo_url is nullable: a row with a null URL still records "we asked Finnhub and it had
-- nothing for this ticker" so ingestion doesn't retry it forever.

CREATE TABLE company_logo (
    ticker      text        PRIMARY KEY,
    logo_url    text,
    fetched_at  timestamptz NOT NULL DEFAULT now()
);
