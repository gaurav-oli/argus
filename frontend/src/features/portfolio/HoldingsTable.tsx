"use client";

import {
  ApiError,
  confirmPositionFx,
  getCash,
  getPortfolioValue,
  setCash,
  type CashBalanceView,
  type PortfolioSnapshot,
  type PositionValue,
} from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useMemo, useState } from "react";

const num = (n: number | null, digits = 2) =>
  n == null ? "—" : n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
const money = (n: number | null) =>
  n == null ? "—" : `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const pct = (n: number | null) => (n == null ? "—" : `${n.toFixed(2)}%`);
const pnlClass = (n: number | null) =>
  n == null || n === 0 ? "text-text-primary" : n > 0 ? "text-gains" : "text-losses";

type GroupedRow = {
  ticker: string;
  companyName: string | null;
  shares: number;
  usdValue: number | null;
  cadValue: number;
  cadCost: number | null;
  cadPnl: number;
  weight: number;
  accounts: PositionValue[];
};

const ALL = "All";

/**
 * The single Holdings table (Story 3.5 + Multi-Bank Holdings). Live from `/topic/portfolio`.
 * Per-account rows with Bank + Account, value in USD / CAD, CAD cost basis (with a set-rate
 * affordance when FX is estimated), and P&L. Uninvested cash is shown here too (as rows, editable),
 * so the table totals to the same figure as the brokerage. Filter by bank/account/currency;
 * "Group by ticker" combines the same stock across accounts.
 */
export function HoldingsTable() {
  const [positions, setPositions] = useState<PositionValue[]>([]);
  const [totalValueCad, setTotalValueCad] = useState(0);
  const [cash, setCashRows] = useState<CashBalanceView[]>([]);
  const [bank, setBank] = useState<string>(ALL);
  const [account, setAccount] = useState<string>(ALL);
  const [currency, setCurrency] = useState<string>(ALL);
  const [grouped, setGrouped] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [fxEditId, setFxEditId] = useState<number | null>(null);
  const [fxRate, setFxRate] = useState("");
  const [fxError, setFxError] = useState<string | null>(null);

  // add-cash form
  const [newAccount, setNewAccount] = useState("");
  const [newCurrency, setNewCurrency] = useState("CAD");
  const [newAmount, setNewAmount] = useState("");

  const applySnapshot = (s: PortfolioSnapshot) => {
    setPositions(s.positions);
    setTotalValueCad(s.totalValueCad ?? 0);
  };
  const refetch = () => getPortfolioValue().then(applySnapshot).catch(() => {});
  const refetchCash = () => getCash().then(setCashRows).catch(() => {});

  useEffect(() => {
    let active = true;
    getPortfolioValue().then((s) => active && applySnapshot(s)).catch(() => {});
    refetchCash();
    const handle = subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", applySnapshot);
    return () => {
      active = false;
      handle.disconnect();
    };
  }, []);

  // USD→CAD rate derived from any priced USD position (for displaying USD cash in CAD).
  const fx = useMemo(() => {
    const p = positions.find(
      (x) => x.currency === "USD" && x.usdMarketValue && x.cadMarketValue,
    );
    return p && p.usdMarketValue ? (p.cadMarketValue ?? 0) / p.usdMarketValue : 1.42;
  }, [positions]);

  const banks = useMemo(() => uniq(positions.map((p) => p.institution)), [positions]);
  const accounts = useMemo(
    () => uniq(positions.filter((p) => bank === ALL || p.institution === bank).map((p) => p.account)),
    [positions, bank],
  );
  const filtered = useMemo(
    () =>
      positions.filter(
        (p) =>
          (bank === ALL || p.institution === bank) &&
          (account === ALL || p.account === account) &&
          (currency === ALL || p.currency === currency),
      ),
    [positions, bank, account, currency],
  );
  const groups = useMemo(() => groupByTicker(filtered), [filtered]);

  // Cash respects the account + currency filters (it has no bank).
  const cashFiltered = useMemo(
    () =>
      cash.filter(
        (c) => (account === ALL || c.account === account) && (currency === ALL || c.currency === currency),
      ),
    [cash, account, currency],
  );
  async function saveFx(id: number) {
    const rate = Number(fxRate);
    if (!Number.isFinite(rate) || rate <= 0) {
      setFxError("Enter a positive USD/CAD rate");
      return;
    }
    try {
      await confirmPositionFx(id, { rate });
      setFxEditId(null);
      setFxRate("");
      setFxError(null);
      await refetch();
    } catch (err) {
      setFxError(err instanceof ApiError ? err.message : "Couldn't set the rate");
    }
  }

  async function removeCash(c: CashBalanceView) {
    await setCash(c.account, c.currency, 0);
    await refetchCash();
  }

  async function addCash() {
    const amt = parseFloat(newAmount);
    if (!newAccount.trim() || !Number.isFinite(amt) || amt <= 0) return;
    await setCash(newAccount.trim(), newCurrency, amt);
    setNewAccount("");
    setNewAmount("");
    await refetchCash();
  }

  if (positions.length === 0 && cash.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-text-primary">Holdings</h3>
        <p className="text-sm text-text-secondary">No holdings yet — import a statement above.</p>
      </div>
    );
  }

  const cashRows = (
    <CashRows rows={cashFiltered} grouped={grouped} total={totalValueCad} fx={fx} onRemove={removeCash} />
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-sm font-medium text-text-primary">
          Holdings
          <span className="ml-2 text-xs font-normal text-text-secondary">
            Total {money(totalValueCad)} CAD
          </span>
        </h3>
        <div className="flex flex-wrap items-center gap-2">
          {banks.length > 1 && (
            <FilterSelect label="Bank" value={bank} options={banks} onChange={(v) => { setBank(v); setAccount(ALL); }} />
          )}
          {accounts.length > 1 && <FilterSelect label="Account" value={account} options={accounts} onChange={setAccount} />}
          <FilterSelect label="Currency" value={currency} options={["CAD", "USD"]} onChange={setCurrency} />
          <button
            onClick={() => setGrouped((g) => !g)}
            aria-pressed={grouped}
            className={`rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors ${
              grouped ? "border-accent/50 bg-accent/10 text-accent" : "border-border text-text-secondary hover:text-text-primary"
            }`}
          >
            Group by ticker
          </button>
        </div>
      </div>

      {fxError && <p className="text-xs text-losses">{fxError}</p>}

      <div className="overflow-x-auto">
        {grouped ? (
          <GroupedTable groups={groups} expanded={expanded} setExpanded={setExpanded} cashRows={cashRows} />
        ) : (
          <FlatTable
            rows={filtered}
            fxEditId={fxEditId}
            fxRate={fxRate}
            setFxRate={setFxRate}
            onEditFx={(id) => { setFxEditId(id); setFxRate(""); setFxError(null); }}
            onSaveFx={saveFx}
            onCancelFx={() => { setFxEditId(null); setFxRate(""); }}
            cashRows={cashRows}
          />
        )}
      </div>

      {/* add cash inline */}
      <div className="flex flex-wrap items-center gap-2 border-t border-border/60 pt-3 text-xs">
        <span className="text-text-secondary">Add cash:</span>
        <input
          list="holdings-cash-accounts"
          value={newAccount}
          onChange={(e) => setNewAccount(e.target.value)}
          placeholder="Account"
          className="min-w-0 flex-1 rounded border border-border bg-background px-2 py-1.5 text-text-primary placeholder:text-text-secondary"
        />
        <datalist id="holdings-cash-accounts">
          {uniq(positions.map((p) => p.account)).map((a) => (
            <option key={a} value={a} />
          ))}
        </datalist>
        <select
          value={newCurrency}
          onChange={(e) => setNewCurrency(e.target.value)}
          className="rounded border border-border bg-background px-2 py-1.5 text-text-primary"
        >
          <option value="CAD">CAD</option>
          <option value="USD">USD</option>
        </select>
        <input
          type="number"
          value={newAmount}
          onChange={(e) => setNewAmount(e.target.value)}
          placeholder="Amount"
          className="w-28 rounded border border-border bg-background px-2 py-1.5 text-text-primary placeholder:text-text-secondary"
        />
        <button onClick={addCash} className="rounded bg-accent/15 px-3 py-1.5 font-medium text-accent hover:bg-accent/25">
          Add
        </button>
      </div>
    </div>
  );
}

/** Cash rows shared by both table layouts (flat = 9 cols, grouped = 8 cols). */
function CashRows({
  rows,
  grouped,
  total,
  fx,
  onRemove,
}: {
  rows: CashBalanceView[];
  grouped: boolean;
  total: number;
  fx: number;
  onRemove: (c: CashBalanceView) => void;
}) {
  if (rows.length === 0) return null;
  return (
    <>
      {rows.map((c) => {
        const cad = c.currency === "CAD" ? c.amount : c.amount * fx;
        const weight = total > 0 ? (cad / total) * 100 : null;
        return (
          <tr key={`cash-${c.id}`} className="border-t border-border/60">
            <td className="py-1.5 pr-3 font-medium text-text-primary">
              💵 Cash
              <button
                onClick={() => onRemove(c)}
                className="ml-2 text-[10px] text-losses hover:underline"
                title="Remove cash"
              >
                remove
              </button>
            </td>
            {grouped ? (
              <td className="hidden py-1.5 pr-3 text-text-secondary md:table-cell">{c.account}</td>
            ) : (
              <>
                <td className="hidden py-1.5 pr-3 text-text-secondary lg:table-cell">—</td>
                <td className="hidden py-1.5 pr-3 text-text-secondary md:table-cell">{c.account}</td>
              </>
            )}
            <td className="py-1.5 pr-3 text-right text-text-secondary">—</td>
            <td className="py-1.5 pr-3 text-right text-text-primary">{c.currency === "USD" ? money(c.amount) : "—"}</td>
            <td className="hidden py-1.5 pr-3 text-right text-text-primary md:table-cell">{money(cad)}</td>
            <td className="hidden py-1.5 pr-3 text-right text-text-primary md:table-cell">{money(cad)}</td>
            <td className="py-1.5 pr-3 text-right text-text-secondary">—</td>
            <td className="hidden py-1.5 pr-3 text-right text-text-secondary lg:table-cell">{pct(weight)}</td>
          </tr>
        );
      })}
    </>
  );
}

function FlatTable({
  rows,
  fxEditId,
  fxRate,
  setFxRate,
  onEditFx,
  onSaveFx,
  onCancelFx,
  cashRows,
}: {
  rows: PositionValue[];
  fxEditId: number | null;
  fxRate: string;
  setFxRate: (v: string) => void;
  onEditFx: (id: number) => void;
  onSaveFx: (id: number) => void;
  onCancelFx: () => void;
  cashRows: React.ReactNode;
}) {
  const sorted = useMemo(() => [...rows].sort((a, b) => (b.weightPercent ?? 0) - (a.weightPercent ?? 0)), [rows]);
  return (
    <table className="w-full text-left text-sm tabular-nums">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-text-secondary">
          <th className="py-1 pr-3 font-medium">Ticker</th>
          <th className="hidden py-1 pr-3 font-medium lg:table-cell">Bank</th>
          <th className="hidden py-1 pr-3 font-medium md:table-cell">Account</th>
          <th className="py-1 pr-3 text-right font-medium">Shares</th>
          <th className="py-1 pr-3 text-right font-medium">Value USD</th>
          <th className="hidden py-1 pr-3 text-right font-medium md:table-cell">Value CAD</th>
          <th className="hidden py-1 pr-3 text-right font-medium md:table-cell">Cost CAD</th>
          <th className="py-1 pr-3 text-right font-medium">P&amp;L</th>
          <th className="hidden py-1 pr-3 text-right font-medium lg:table-cell">Weight</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((p) => (
          <tr key={p.id} className="border-t border-border/60">
            <td className="py-1.5 pr-3 font-medium text-text-primary">
              {p.ticker}
              {p.afterHours && <span className="ml-1.5 text-[10px] text-warning">AH</span>}
              {p.companyName && <span className="ml-2 hidden text-xs text-text-secondary xl:inline">{p.companyName}</span>}
            </td>
            <td className="hidden py-1.5 pr-3 text-text-secondary lg:table-cell">{p.institution ?? "—"}</td>
            <td className="hidden py-1.5 pr-3 text-text-secondary md:table-cell">{p.account ?? "—"}</td>
            <td className="py-1.5 pr-3 text-right text-text-primary">{num(p.shares, 0)}</td>
            <td className="py-1.5 pr-3 text-right text-text-primary">{money(p.usdMarketValue)}</td>
            <td className="hidden py-1.5 pr-3 text-right text-text-primary md:table-cell">{money(p.cadMarketValue)}</td>
            <td className="hidden py-1.5 pr-3 text-right md:table-cell">
              <span className="text-text-primary">{money(p.cadAcb)}</span>
              {p.fxEstimated &&
                (fxEditId === p.id ? (
                  <span className="ml-2 inline-flex items-center gap-1">
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      value={fxRate}
                      onChange={(e) => setFxRate(e.target.value)}
                      placeholder="USD/CAD"
                      className="w-20 rounded border border-border bg-background px-1.5 py-0.5 text-xs text-text-primary"
                    />
                    <button onClick={() => onSaveFx(p.id)} className="text-xs font-medium text-accent hover:underline">
                      Save
                    </button>
                    <button onClick={onCancelFx} className="text-xs text-text-secondary hover:text-text-primary">
                      ✕
                    </button>
                  </span>
                ) : (
                  <button
                    onClick={() => onEditFx(p.id)}
                    className="ml-1.5 text-[10px] text-warning hover:underline"
                    title="FX estimated — set the purchase-time USD/CAD rate"
                  >
                    est.
                  </button>
                ))}
            </td>
            <td className={`py-1.5 pr-3 text-right ${pnlClass(p.cadPnl)}`}>{money(p.cadPnl)}</td>
            <td className="hidden py-1.5 pr-3 text-right text-text-secondary lg:table-cell">{pct(p.weightPercent)}</td>
          </tr>
        ))}
        {cashRows}
      </tbody>
    </table>
  );
}

function GroupedTable({
  groups,
  expanded,
  setExpanded,
  cashRows,
}: {
  groups: GroupedRow[];
  expanded: string | null;
  setExpanded: (t: string | null) => void;
  cashRows: React.ReactNode;
}) {
  const sorted = useMemo(() => [...groups].sort((a, b) => b.cadValue - a.cadValue), [groups]);
  return (
    <table className="w-full text-left text-sm tabular-nums">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-text-secondary">
          <th className="py-1 pr-3 font-medium">Ticker</th>
          <th className="hidden py-1 pr-3 font-medium md:table-cell">Accounts</th>
          <th className="py-1 pr-3 text-right font-medium">Shares</th>
          <th className="py-1 pr-3 text-right font-medium">Value USD</th>
          <th className="hidden py-1 pr-3 text-right font-medium md:table-cell">Value CAD</th>
          <th className="hidden py-1 pr-3 text-right font-medium md:table-cell">Cost CAD</th>
          <th className="py-1 pr-3 text-right font-medium">P&amp;L</th>
          <th className="hidden py-1 pr-3 text-right font-medium lg:table-cell">Weight</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((g) => {
          const open = expanded === g.ticker;
          const multi = g.accounts.length > 1;
          return (
            <Frag key={g.ticker}>
              <tr
                onClick={() => multi && setExpanded(open ? null : g.ticker)}
                className={`border-t border-border/60 ${multi ? "cursor-pointer" : ""}`}
              >
                <td className="py-1.5 pr-3 font-medium text-text-primary">
                  {multi && <span className="mr-1.5 inline-block w-2 text-text-secondary">{open ? "▾" : "▸"}</span>}
                  {g.ticker}
                  {g.companyName && <span className="ml-2 hidden text-xs text-text-secondary xl:inline">{g.companyName}</span>}
                </td>
                <td className="hidden py-1.5 pr-3 text-text-secondary md:table-cell">
                  {g.accounts.length} account{g.accounts.length === 1 ? "" : "s"}
                </td>
                <td className="py-1.5 pr-3 text-right text-text-primary">{num(g.shares, 0)}</td>
                <td className="py-1.5 pr-3 text-right text-text-primary">{money(g.usdValue)}</td>
                <td className="hidden py-1.5 pr-3 text-right text-text-primary md:table-cell">{money(g.cadValue)}</td>
                <td className="hidden py-1.5 pr-3 text-right text-text-primary md:table-cell">{money(g.cadCost)}</td>
                <td className={`py-1.5 pr-3 text-right ${pnlClass(g.cadPnl)}`}>{money(g.cadPnl)}</td>
                <td className="hidden py-1.5 pr-3 text-right text-text-secondary lg:table-cell">{pct(g.weight)}</td>
              </tr>
              {open &&
                g.accounts.map((a, i) => (
                  <tr key={`${g.ticker}-${i}`} className="bg-[var(--hover-wash)] text-xs">
                    <td className="py-1 pr-3 pl-7 text-text-secondary" colSpan={2}>
                      <span className="text-text-primary">{a.institution ?? "—"}</span> · {a.account ?? "—"}
                    </td>
                    <td className="py-1 pr-3 text-right text-text-secondary">{num(a.shares, 0)}</td>
                    <td className="py-1 pr-3 text-right text-text-secondary">{money(a.usdMarketValue)}</td>
                    <td className="hidden py-1 pr-3 text-right text-text-secondary md:table-cell">{money(a.cadMarketValue)}</td>
                    <td className="hidden py-1 pr-3 text-right text-text-secondary md:table-cell">{money(a.cadAcb)}</td>
                    <td className={`py-1 pr-3 text-right ${pnlClass(a.cadPnl)}`}>{money(a.cadPnl)}</td>
                    <td className="hidden py-1 pr-3 text-right text-text-secondary lg:table-cell">{pct(a.weightPercent)}</td>
                  </tr>
                ))}
            </Frag>
          );
        })}
        {cashRows}
      </tbody>
    </table>
  );
}

function Frag({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}

function FilterSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: string[];
  onChange: (v: string) => void;
}) {
  return (
    <label className="inline-flex items-center gap-1.5 text-xs text-text-secondary">
      <span className="hidden sm:inline">{label}</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="cursor-pointer rounded-lg border border-border bg-background px-2 py-1.5 text-xs font-medium text-text-primary transition-colors hover:border-accent focus:border-accent focus:outline-none"
      >
        <option value={ALL}>{ALL}</option>
        {options.map((o) => (
          <option key={o} value={o}>
            {o}
          </option>
        ))}
      </select>
    </label>
  );
}

function uniq(values: (string | null)[]): string[] {
  return [...new Set(values.filter((v): v is string => Boolean(v)))].sort();
}

function groupByTicker(rows: PositionValue[]): GroupedRow[] {
  const map = new Map<string, GroupedRow>();
  for (const p of rows) {
    const g =
      map.get(p.ticker) ??
      { ticker: p.ticker, companyName: p.companyName, shares: 0, usdValue: null, cadValue: 0, cadCost: null, cadPnl: 0, weight: 0, accounts: [] };
    g.shares += p.shares ?? 0;
    g.cadValue += p.cadMarketValue ?? 0;
    g.cadPnl += p.cadPnl ?? 0;
    g.weight += p.weightPercent ?? 0;
    if (p.usdMarketValue != null) g.usdValue = (g.usdValue ?? 0) + p.usdMarketValue;
    if (p.cadAcb != null) g.cadCost = (g.cadCost ?? 0) + p.cadAcb;
    if (!g.companyName && p.companyName) g.companyName = p.companyName;
    g.accounts.push(p);
    map.set(p.ticker, g);
  }
  return [...map.values()];
}
