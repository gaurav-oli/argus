-- V4__app_settings.sql — single-row app/user settings (Story 2.3).
--
-- Argus is single-user, so one settings row (pinned to id = 1). Starts with the
-- configurable session timeout; future stories add columns (e.g. panic-mode gesture).
-- session_timeout_seconds NULL = "Never" (no idle expiry). Forward-only.

CREATE TABLE app_settings (
    id                       smallint     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    session_timeout_seconds  bigint,
    updated_at               timestamptz  NOT NULL DEFAULT now()
);
