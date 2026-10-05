-- V73__drop_pin_webauthn.sql — the PIN/WebAuthn login flow (Story 2.1/2.2/2.6) is retired: Google
-- Sign-In is the only way in now (Phase 2, multi-user). Drops the two tables it owned; nothing else
-- referenced them (no foreign keys point at either).

drop table if exists webauthn_credential;
drop table if exists app_credential;
