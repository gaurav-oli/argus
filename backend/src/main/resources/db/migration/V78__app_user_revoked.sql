-- V78__app_user_revoked.sql — the admin can revoke someone's access without deleting their data.
-- An existing account signs in by its Google subject id and was never re-checked against the invite
-- list, so removing the invite alone could not lock a joined user out. A non-null revoked_at blocks
-- sign-in (and every request, via CurrentUserService) until the admin restores access.

alter table app_user add column revoked_at timestamptz;
