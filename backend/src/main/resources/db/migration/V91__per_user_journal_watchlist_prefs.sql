-- S-C1 (finding H3): one friend's decisions and preferences no longer rewrite everyone else's.
-- The admin is the instance owner; everything recorded before multi-user belongs to them.

-- 1. Trade Journal. A USER decision belongs to the person who made it; AGENT decisions (the paper Investor)
--    stay shared (user_id null) — that is the shared research everyone sees.
alter table trade_decisions add column user_id bigint references app_user (id) on delete cascade;
update trade_decisions set user_id = (select id from app_user where is_admin order by id limit 1) where source = 'USER';
create index trade_decisions_user_idx on trade_decisions (user_id);

-- 2. Watchlist. A MANUAL pick belongs to the person who added it; DISCOVERED entries are the system's
--    (user_id null). The agents still cover the union (CompositeKnownUniverse) — coverage is shared research —
--    but each person sees and edits only their own picks (plus the discoveries).
alter table watchlist add column user_id bigint references app_user (id) on delete cascade;
update watchlist set user_id = (select id from app_user where is_admin order by id limit 1) where source = 'MANUAL';
alter table watchlist drop constraint watchlist_ticker_key;
create unique index watchlist_owner_ticker_uq on watchlist (coalesce(user_id, 0), ticker);
create index watchlist_ticker_idx on watchlist (ticker);

-- 3. Notification preferences, one row per person (defaults when missing). The old singleton row seeds
--    everyone who exists today, so nobody's settings change on upgrade. notification_prefs is left in place
--    (unused) for a safe rollback.
create table user_notification_prefs (
    user_id           bigint       primary key references app_user (id) on delete cascade,
    briefing_enabled  boolean      not null default true,
    breaking_enabled  boolean      not null default true,
    alerts_enabled    boolean      not null default true,
    quiet_start_hour  smallint,
    quiet_end_hour    smallint,
    muted_tickers     text[]       not null default '{}',
    updated_at        timestamptz  not null default now()
);
insert into user_notification_prefs (user_id, briefing_enabled, breaking_enabled, alerts_enabled, quiet_start_hour,
                                     quiet_end_hour, muted_tickers)
select u.id, p.briefing_enabled, p.breaking_enabled, p.alerts_enabled, p.quiet_start_hour, p.quiet_end_hour, p.muted_tickers
  from app_user u cross join notification_prefs p where p.id = 1;
