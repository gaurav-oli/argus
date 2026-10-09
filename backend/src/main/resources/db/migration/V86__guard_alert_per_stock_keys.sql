-- V86__guard_alert_per_stock_keys.sql — guard alerts are now one per STOCK, not one per account (AMZN held in five
-- accounts alerted five times). Existing dedupe keys were KIND:account:TICKER:suffix; rewrite the latest one per
-- (person, KIND:TICKER:suffix) to the new account-free form so nothing already alerted is alerted again.
update guard_alert g
set dedupe_key = k.new_key
from (
    select max(id) as id, user_id,
           split_part(dedupe_key, ':', 1) || ':' || split_part(dedupe_key, ':', 3) || ':' || split_part(dedupe_key, ':', 4) as new_key
    from guard_alert
    where array_length(string_to_array(dedupe_key, ':'), 1) = 4
    group by user_id, 3
) k
where g.id = k.id;
