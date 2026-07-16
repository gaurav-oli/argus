-- First-class account TYPE for cross-bank grouping. Until now the type (TFSA/RRSP/Cash/…) was
-- derived on the fly from the `positions.account` label — which works for National Bank (whose label
-- carries the type, e.g. "687WQD-7 USD RRSP") but NOT for banks like RBC, whose account label is just
-- a number ("229-68511-1-2") and whose type lives in the statement HEADER ("TFSA Statement",
-- "RRSP Statement", "Cdn. Dollar Statement" = Cash). Storing a normalized type at import time lets the
-- household ledger group holdings by (owner + type) across banks (NBDB TFSA + RBC TFSA → one TFSA
-- rollup) without depending on any bank's label formatting. Null when the parser couldn't tell; the
-- read path then falls back to parsing the label.
ALTER TABLE account_meta ADD COLUMN account_type text; -- normalized: TFSA|RRSP|RRIF|RESP|LIRA|Margin|Cash|Corporate|… (null = unknown)

-- Staged parsed accounts already round-trip as JSON in portfolio_imports.raw_accounts (V39); the new
-- ParsedAccount.accountType field rides along in that JSON with no schema change here.
