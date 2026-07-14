-- Notification preferences (single-user, singleton row). A central gate consults these before any
-- Web Push fan-out: per-type toggles (morning briefing / breaking news / other alerts), quiet hours
-- (local time; suppress non-critical pushes overnight), and per-ticker mutes. CRITICAL-tier alerts
-- bypass quiet hours but still respect the master toggle + mutes.
CREATE TABLE notification_prefs (
    id                smallint     PRIMARY KEY DEFAULT 1,
    briefing_enabled  boolean      NOT NULL DEFAULT true,
    breaking_enabled  boolean      NOT NULL DEFAULT true,
    alerts_enabled    boolean      NOT NULL DEFAULT true,
    quiet_start_hour  smallint,                       -- 0-23 local; NULL (either) = no quiet hours
    quiet_end_hour    smallint,
    muted_tickers     text[]       NOT NULL DEFAULT '{}',
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT notification_prefs_singleton CHECK (id = 1)
);

INSERT INTO notification_prefs (id) VALUES (1) ON CONFLICT DO NOTHING;
