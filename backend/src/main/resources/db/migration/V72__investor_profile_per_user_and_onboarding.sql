-- V72__investor_profile_per_user_and_onboarding.sql — the investor profile (Story 7.6) was a single
-- global row; with multiple real people now signing in, each person needs their own, keyed by their
-- own user_id as the primary key (a strict one-row-per-person table, same shape as before, just no
-- longer hardcoded to id=1). Also adds the two new first-login onboarding fields: trading_horizon
-- (long-term holder / active trader / mix) and onboarding_completed_at (null = show the first-login
-- questions; set the moment anyone saves a profile, whether via onboarding or later in Settings).

do $$
declare
    admin_id bigint;
begin
    select id into admin_id from app_user where lower(email) = 'gauravoli16@gmail.com';

    alter table investor_profile add column user_id bigint references app_user (id);
    alter table investor_profile add column trading_horizon text; -- LONG_TERM_HOLDER | ACTIVE_TRADER | MIX
    alter table investor_profile add column onboarding_completed_at timestamptz;

    if admin_id is not null then
        update investor_profile set user_id = admin_id where user_id is null;
    else
        -- No admin provisioned yet (e.g. a fresh test database) — the only row is V44's unconditional
        -- placeholder default, nothing real to preserve.
        delete from investor_profile where user_id is null;
    end if;
end $$;

alter table investor_profile drop constraint investor_profile_singleton;
alter table investor_profile drop constraint investor_profile_pkey;
alter table investor_profile drop column id;
alter table investor_profile alter column user_id set not null;
alter table investor_profile add primary key (user_id);
