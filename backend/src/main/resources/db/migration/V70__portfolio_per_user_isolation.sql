-- V70__portfolio_per_user_isolation.sql — Phase 2 multi-user: wall every portfolio/financial table
-- off per person (Hibernate @TenantId on user_id — see PortfolioTenantResolver). Every existing row
-- predates multi-user, so it all backfills to the admin's own account; a friend's first import then
-- starts them with a clean, empty portfolio of their own.
--
-- Binding privacy rule (2026-10): nobody, including the admin, can read anyone else's portfolio
-- data — only ticker SYMBOLS are ever merged across users (via a native query that deliberately
-- bypasses this column; see PositionRepository.allTickersAcrossAllUsers).

do $$
declare
    admin_id bigint;
    existing_rows bigint;
begin
    select id into admin_id from app_user where lower(email) = 'gauravoli16@gmail.com';

    select (select count(*) from positions) + (select count(*) from position_lots)
         + (select count(*) from position_audit) + (select count(*) from cash_balances)
         + (select count(*) from account_meta) + (select count(*) from corporate_actions)
         + (select count(*) from portfolio_imports) + (select count(*) from portfolio_value_history)
         + (select count(*) from health_score)
    into existing_rows;

    -- A brand-new install (e.g. a throwaway test database) has no pre-multi-user rows to backfill, so
    -- no admin account needs to exist yet either. A real deployment with real data, though, must have
    -- already run Google Sign-In once (V68/V69) before this migration — otherwise there is nobody to
    -- attribute the existing rows to, and this fails loudly rather than silently orphaning them.
    if existing_rows > 0 and admin_id is null then
        raise exception 'V70: % existing portfolio row(s) but no admin app_user — sign in with Google first', existing_rows;
    end if;

    alter table positions          add column user_id bigint references app_user (id);
    alter table position_lots      add column user_id bigint references app_user (id);
    alter table position_audit     add column user_id bigint references app_user (id);
    alter table cash_balances      add column user_id bigint references app_user (id);
    alter table account_meta       add column user_id bigint references app_user (id);
    alter table corporate_actions  add column user_id bigint references app_user (id);
    alter table portfolio_imports  add column user_id bigint references app_user (id);
    alter table portfolio_value_history add column user_id bigint references app_user (id);
    alter table health_score       add column user_id bigint references app_user (id);

    update positions             set user_id = admin_id where user_id is null;
    update position_lots         set user_id = admin_id where user_id is null;
    update position_audit        set user_id = admin_id where user_id is null;
    update cash_balances         set user_id = admin_id where user_id is null;
    update account_meta          set user_id = admin_id where user_id is null;
    update corporate_actions     set user_id = admin_id where user_id is null;
    update portfolio_imports     set user_id = admin_id where user_id is null;
    update portfolio_value_history set user_id = admin_id where user_id is null;
    update health_score          set user_id = admin_id where user_id is null;
end $$;

alter table positions          alter column user_id set not null;
alter table position_lots      alter column user_id set not null;
alter table position_audit     alter column user_id set not null;
alter table cash_balances      alter column user_id set not null;
alter table account_meta       alter column user_id set not null;
alter table corporate_actions  alter column user_id set not null;
alter table portfolio_imports  alter column user_id set not null;
alter table portfolio_value_history alter column user_id set not null;
alter table health_score       alter column user_id set not null;

create index idx_positions_user_id         on positions (user_id);
create index idx_position_lots_user_id     on position_lots (user_id);
create index idx_position_audit_user_id    on position_audit (user_id);
create index idx_cash_balances_user_id     on cash_balances (user_id);
create index idx_account_meta_user_id      on account_meta (user_id);
create index idx_corporate_actions_user_id on corporate_actions (user_id);
create index idx_portfolio_imports_user_id on portfolio_imports (user_id);
create index idx_portfolio_value_history_user_id on portfolio_value_history (user_id);
create index idx_health_score_user_id      on health_score (user_id);

-- These two were globally unique (one row per day, full stop); now one row per day PER PERSON.
alter table portfolio_value_history drop constraint portfolio_value_history_captured_on_key;
alter table portfolio_value_history add constraint uq_portfolio_value_history_user_date unique (user_id, captured_on);

alter table health_score drop constraint health_score_scored_on_key;
alter table health_score add constraint uq_health_score_user_date unique (user_id, scored_on);
