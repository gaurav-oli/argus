-- S-B6: per-style strategy fit. Each paper leg records the playbook it led with (the evidence family of
-- its lead signal: NEWS, DEEP, TECHNICAL, ...) and what the style-fit matrix said about it at entry.
-- The style dimensions themselves (vol, sector, price, regime, trend) are tokens in setup_fingerprint (V88).
alter table simulated_trades add column playbook varchar(20);
alter table simulated_trades add column style_fit text;
create index simulated_trades_playbook_idx on simulated_trades (playbook) where playbook is not null;
