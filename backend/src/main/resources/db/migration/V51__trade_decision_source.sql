-- Distinguishes who made a Taken/Declined call: the Investor persona acting autonomously on its own
-- paper trades (AGENT) vs a human clicking the card buttons (USER). Default 'USER' needs no backfill
-- statement here — the one existing row genuinely is a human decision; historical AGENT rows are
-- backfilled in Java (TradeConfirmationService's startup pass), since building each row's frozen
-- snapshot JSON isn't something a SQL migration can replicate.
alter table trade_decisions add column source text not null default 'USER';
