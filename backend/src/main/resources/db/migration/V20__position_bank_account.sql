-- Multi-bank holdings: which institution (bank) + account each position belongs to, so statements
-- from different banks/accounts can coexist and a re-import reconciles within its own scope.
ALTER TABLE positions ADD COLUMN institution text;
ALTER TABLE positions ADD COLUMN account text;

-- The bank is chosen at upload time and carried on the staged import batch until confirm.
ALTER TABLE portfolio_imports ADD COLUMN institution text;

-- Speeds the reconcile lookup (existing holdings for a bank).
CREATE INDEX idx_positions_institution ON positions (institution);
