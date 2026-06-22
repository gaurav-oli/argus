-- V3__webauthn_credential.sql — registered passkeys / WebAuthn credentials (Story 2.2).
--
-- Argus is single-user, but the user may enroll several passkeys (iPhone, iPad, …),
-- so this is a multi-row table keyed by the WebAuthn credential id. The PIN
-- (app_credential, V2) remains the fallback; removing all rows here leaves
-- PIN-only login intact. Forward-only; never edit an applied migration.

CREATE TABLE webauthn_credential (
    credential_id    bytea        PRIMARY KEY,
    user_handle      bytea        NOT NULL,
    public_key_cose  bytea        NOT NULL,
    signature_count  bigint       NOT NULL DEFAULT 0,
    label            text         NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    last_used_at     timestamptz
);

CREATE INDEX idx_webauthn_credential_user_handle ON webauthn_credential (user_handle);
