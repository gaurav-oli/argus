-- Investor profile (single-user, singleton row) — Story 7.6. Captures the parts of the investor profile
-- that can't be derived from imported accounts: risk tolerance, financial goal, and a target (amount +
-- date), plus editable residency / home currency (which override the argus.investor.* config defaults)
-- and free-text preferences. Consumed by InvestorProfileService (portfolio-chat grounding, FR-31) and the
-- Canadian persona (FR-34). Every field is nullable — a blank profile falls back to the config/derived
-- defaults, so existing behavior is unchanged until the user edits it.
CREATE TABLE investor_profile (
    id             smallint       PRIMARY KEY DEFAULT 1,
    risk_tolerance text,                          -- CONSERVATIVE | BALANCED | GROWTH | AGGRESSIVE (NULL = unset)
    financial_goal text,
    target_amount  numeric(18,2),                 -- destination amount in the home currency (Goal Tracker)
    target_date    date,
    residency      text,                          -- overrides argus.investor.residency when set
    home_currency  text,                          -- overrides argus.investor.home-currency when set
    notes          text,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT investor_profile_singleton CHECK (id = 1)
);

INSERT INTO investor_profile (id) VALUES (1) ON CONFLICT DO NOTHING;
