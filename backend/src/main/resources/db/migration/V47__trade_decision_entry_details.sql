-- Trade Journal (Story 11.1, F22): optionally capture entry price + position size when a
-- recommendation is marked Taken. Both nullable — omitting them must not block confirming a
-- decision (matches the existing optional-reasoning posture), and existing trade_decisions rows
-- have no value here (the journal renders them as "not recorded", not zero).
ALTER TABLE trade_decisions
    ADD COLUMN entry_price   numeric(18,4),
    ADD COLUMN position_size numeric(18,4);
