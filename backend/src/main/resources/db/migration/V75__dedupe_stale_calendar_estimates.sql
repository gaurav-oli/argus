-- V75__dedupe_stale_calendar_estimates.sql — one-time cleanup of the "duplicate" earnings/IPO rows
-- left behind when Finnhub revised a date ESTIMATE before the real date was confirmed. Each revision
-- was stored as a brand-new row (external_id is date-keyed), so a ticker could accumulate several
-- never-reported rows for what was really one shifting estimate (Agent7CalendarService#store now
-- supersedes these going forward instead of accumulating them).
--
-- Keeps, per (source, type, ticker) with no reported result yet (eps_actual is null — true forever
-- for IPO, true until the actual EPS lands for EARNINGS), only the most recently ingested row; the
-- superseded guesses are deleted. calendar_event_alerts cascades automatically (ON DELETE CASCADE),
-- so any lead-time alert that fired against a now-deleted, never-real date goes with it.

delete from calendar_events c
using (
    select id,
           row_number() over (
               partition by source, type, ticker
               order by ingested_at desc
           ) as rn
    from calendar_events
    where ticker is not null
      and eps_actual is null
) ranked
where c.id = ranked.id
  and ranked.rn > 1;
