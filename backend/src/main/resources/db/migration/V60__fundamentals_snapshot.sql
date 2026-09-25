-- Agent 12 (Fundamentals). One latest snapshot per ticker so the recommender's hot path reads the
-- company's fundamental picture from the database instead of calling Finnhub (10 calls per ticker).
-- payload is the serialized Fundamentals record; applicable=false marks ETFs / symbols with no company
-- data so they are not re-fetched every night.
CREATE TABLE fundamentals_snapshot (
    ticker      text          PRIMARY KEY,
    payload     text          NOT NULL,
    applicable  boolean       NOT NULL,
    score       numeric(6,3)  NOT NULL,
    bias        text          NOT NULL,
    fetched_at  timestamptz   NOT NULL DEFAULT now()
);
