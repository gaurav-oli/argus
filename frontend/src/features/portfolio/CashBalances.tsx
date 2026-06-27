"use client";

import { getCash, getPortfolioValue, setCash, type CashBalanceView } from "@/lib/apiClient";
import { useEffect, useState } from "react";

/**
 * Cash balances editor (FR-1 gap). The PDF import only captures securities, so the brokerage's
 * total reads higher by the uninvested cash. Recording it here folds it into the live portfolio
 * total (added to value and cost equally, so P&L is unaffected).
 */
export function CashBalances() {
  const [rows, setRows] = useState<CashBalanceView[] | null>(null);
  const [accounts, setAccounts] = useState<string[]>([]);
  const [account, setAccount] = useState("");
  const [currency, setCurrency] = useState("CAD");
  const [amount, setAmount] = useState("");
  const [busy, setBusy] = useState(false);

  const load = () => getCash().then(setRows).catch(() => setRows([]));

  useEffect(() => {
    load();
    getPortfolioValue()
      .then((s) => {
        const accts = Array.from(
          new Set(s.positions.map((p) => p.account).filter((a): a is string => Boolean(a))),
        ).sort();
        setAccounts(accts);
      })
      .catch(() => {});
  }, []);

  async function save(acct: string, cur: string, amt: number) {
    setBusy(true);
    try {
      await setCash(acct, cur, amt);
      await load();
    } finally {
      setBusy(false);
    }
  }

  async function add() {
    const amt = parseFloat(amount);
    if (!account.trim() || !Number.isFinite(amt) || amt <= 0) return;
    await save(account.trim(), currency, amt);
    setAccount("");
    setAmount("");
  }

  const totalCad = (rows ?? []).reduce(
    (sum, r) => sum + (r.currency === "CAD" ? r.amount : r.amount * 1.42),
    0,
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-baseline justify-between">
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Cash balances</h2>
        {rows && rows.length > 0 && (
          <span className="text-xs text-text-secondary">
            ≈ <span className="font-medium text-text-primary tabular-nums">
              {totalCad.toLocaleString(undefined, { maximumFractionDigits: 0 })} CAD
            </span>{" "}
            (folded into total)
          </span>
        )}
      </div>

      {rows === null ? (
        <p className="text-sm text-text-secondary">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-xs text-text-secondary">
          No cash recorded. Add your uninvested cash per account so the total matches your brokerage.
        </p>
      ) : (
        <ul className="flex flex-col divide-y divide-border/50">
          {rows.map((r) => (
            <li key={r.id} className="flex items-center gap-2 py-2 text-sm">
              <span className="min-w-0 flex-1 truncate text-text-primary">{r.account}</span>
              <span className="w-10 shrink-0 text-xs text-text-secondary">{r.currency}</span>
              <span className="w-28 shrink-0 text-right font-mono tabular-nums text-text-primary">
                {r.amount.toLocaleString(undefined, { minimumFractionDigits: 2 })}
              </span>
              <button
                disabled={busy}
                onClick={() => save(r.account, r.currency, 0)}
                className="shrink-0 rounded px-2 py-1 text-[11px] text-losses hover:bg-losses/10 disabled:opacity-50"
                title="Remove"
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
      )}

      {/* add row */}
      <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
        <input
          list="cash-accounts"
          value={account}
          onChange={(e) => setAccount(e.target.value)}
          placeholder="Account (e.g. 687WK3-A CAD Cash)"
          className="min-w-0 flex-1 rounded border border-border bg-surface px-2 py-1.5 text-sm text-text-primary placeholder:text-text-secondary"
        />
        <datalist id="cash-accounts">
          {accounts.map((a) => (
            <option key={a} value={a} />
          ))}
        </datalist>
        <select
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
          className="rounded border border-border bg-surface px-2 py-1.5 text-sm text-text-primary"
        >
          <option value="CAD">CAD</option>
          <option value="USD">USD</option>
        </select>
        <input
          type="number"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="Amount"
          className="w-32 rounded border border-border bg-surface px-2 py-1.5 text-sm text-text-primary placeholder:text-text-secondary"
        />
        <button
          disabled={busy}
          onClick={add}
          className="rounded bg-accent/15 px-3 py-1.5 text-xs font-medium text-accent hover:bg-accent/25 disabled:opacity-50"
        >
          Add cash
        </button>
      </div>
    </div>
  );
}
