-- Stage parsed cash balances alongside holdings on a pending import (Story 3.1 cash extraction).
ALTER TABLE portfolio_imports ADD COLUMN raw_cash text NOT NULL DEFAULT '[]';
