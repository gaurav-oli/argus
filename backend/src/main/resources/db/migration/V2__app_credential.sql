-- V2__app_credential.sql — single-user PIN credential (Story 2.1).
--
-- Argus is a single-user platform. This table holds exactly one row (the
-- owner's PIN hash). The CHECK (id = 1) constraint makes "more than one
-- credential" unrepresentable. The PIN is stored only as an Argon2id hash —
-- never plaintext. Forward-only; never edit an applied migration.

CREATE TABLE app_credential (
    id         smallint     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    pin_hash   text         NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);
