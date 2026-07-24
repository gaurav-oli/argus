-- V45__calendar_earnings_results.sql — actual-vs-estimated EPS for reported earnings, so the
-- calendar UI can show a beat/miss badge on recent past events (not just upcoming dates).
-- Null until the report lands; Agent 7 revisits recent past dates (CalendarProperties.earningsLookbackDays)
-- and backfills these via CalendarEvent#updateEarningsResult once Finnhub has actual EPS.

ALTER TABLE calendar_events
    ADD COLUMN eps_actual double precision,
    ADD COLUMN eps_estimate double precision,
    ADD COLUMN eps_surprise_percent double precision;
