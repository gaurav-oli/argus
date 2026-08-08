-- Epic 4 follow-up: carry the source's own related-ticker metadata (e.g. Finnhub company-news
-- `related`) alongside the existing held-only `tickers` relevance tags, so Stranger Danger can widen
-- recall beyond $cashtag mentions without changing `tickers`' existing held-universe semantics.
alter table news_articles add column related_tickers text[] not null default '{}';
