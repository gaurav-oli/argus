-- V71__briefing_and_push_per_user.sql — Phase 2 multi-user, continued: the morning Briefing is
-- personal (it narrates one person's real portfolio numbers in plain English), so it gets the same
-- @TenantId treatment as the 9 portfolio tables in V70. Push subscriptions get a plain (not
-- @TenantId) user_id so a personal push (the Briefing) can target one person's device(s) without
-- taking away sendToAll's ability to broadcast shared content (breaking news) to everyone.

do $$
declare
    admin_id bigint;
    existing_rows bigint;
begin
    select id into admin_id from app_user where lower(email) = 'gauravoli16@gmail.com';
    select count(*) from briefings into existing_rows;

    if existing_rows > 0 and admin_id is null then
        raise exception 'V71: % existing briefing row(s) but no admin app_user — sign in with Google first', existing_rows;
    end if;

    alter table briefings add column user_id bigint references app_user (id);
    update briefings set user_id = admin_id where user_id is null;
end $$;

alter table briefings alter column user_id set not null;
create index idx_briefings_user_id on briefings (user_id);

-- Nullable: an existing device is still usable for sendToAll (shared alerts) even before its owner
-- re-subscribes; it just won't receive a personal push (the Briefing) until then. Still backfilled to
-- the admin, since every pre-multi-user subscription was in fact the admin's own device.
alter table push_subscriptions add column user_id bigint references app_user (id);
update push_subscriptions set user_id = (select id from app_user where lower(email) = 'gauravoli16@gmail.com')
    where user_id is null;
create index idx_push_subscriptions_user_id on push_subscriptions (user_id);
