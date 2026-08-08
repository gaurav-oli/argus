-- Data-retention deferred enhancement: tag firehose rows the Smart Cleanup agent spared specifically
-- because they're event-anchored (a precedent worth recognising later), so a future feature can find
-- them directly instead of re-deriving the anchor join every time.
alter table social_posts add column is_precedent boolean not null default false;
alter table web_mentions add column is_precedent boolean not null default false;
alter table news_articles add column is_precedent boolean not null default false;
