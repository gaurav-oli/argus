-- V84__scale_in_legs.sql — the paper investor may add to a winning position once (half size) when Agent 5's
-- conviction on the same thesis rises; the add-on leg is marked so it is never added to again.
alter table simulated_trades add column scale_in boolean not null default false;
