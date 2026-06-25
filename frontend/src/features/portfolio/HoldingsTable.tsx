"use client";

import { getPortfolioValue, type PortfolioSnapshot, type PositionValue } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useMemo, useState } from "react";

const num = (n: number | null, digits = 2) =>
  n == null ? "—" : n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
const pct = (n: number | null) => (n == null ? "—" : `${n.toFixed(2)}%`);
const cad = (n: number) => `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const pnlClass = (n: number | null) =>
  n == null || n === 0 ? "text-text-primary" : n > 0 ? "text-gains" : "text-losses";

type GroupedRow = {
  ticker: string;
  companyName: string | null;
  shares: number;
  cadValue: number;
  cadPnl: number;
  weight: number;
  accounts: PositionValue[];
};

const ALL = "All";

/**
 * Holdings table (Story 3.5 + Multi-Bank Holdings). Driven by the live `/topic/portfolio` snapshot.
 * Per-account rows carry Bank + Account; filter by bank/account/currency; "Group by ticker" combines
 * the same stock across accounts into one CAD-summed row, expandable to its per-account split.
 */
export function HoldingsTable() {
  const [positions, setPositions] = useState<PositionValue[]>([]);
  const [bank, setBank] = useState<string>(ALL);
  const [account, setAccount] = useState<string>(ALL);
  const [currency, setCurrency] = useState<string>(ALL);
  const [grouped, setGrouped] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getPortfolioValue()
      .then((s) => active && setPositions(s.positions))
      .catch(() => {});
    const handle = subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", (s) => setPositions(s.positions));
    return () => {
      active = false;
      handle.disconnect();
    };
  }, []);

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

  if (positions.length === 0) {
    return (
      <div className="flex flex-col gap-2">
        <h3 className="text-sm font-medium text-text-primary">Holdings</h3>
        <p className="text-sm text-text-secondary">No holdings yet — import a statement above.</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-sm font-medium text-text-primary">Holdings</h3>
        <div className="flex flex-wrap items-center gap-2">
          {banks.length > 1 && (
            <Filter label="Bank" value={bank} options={banks} onChange={(v) => { setBank(v); setAccount(ALL); }} />
          )}
          {accounts.length > 1 && <Filter label="Account" value={account} options={accounts} onChange={setAccount} />}
          <Filter label="Currency" value={currency} options={["CAD", "USD"]} onChange={setCurrency} />
          <button
            onClick={() => setGrouped((g) => !g)}
            aria-pressed={grouped}
            className={`rounded-lg border px-2.5 py-1.5 text-xs font-medium transition-colors ${
              grouped
                ? "border-accent/50 bg-accent/10 text-accent"
                : "border-border text-text-secondary hover:text-text-primary"
            }`}
          >
            Group by ticker
          </button>
        </div>
      </div>

      <div className="overflow-x-auto">
        {grouped ? (
          <GroupedTable groups={groups} expanded={expanded} setExpanded={setExpanded} />
        ) : (
          <FlatTable rows={filtered} />
        )}
      </div>
    </div>
  );
}

function FlatTable({ rows }: { rows: PositionValue[] }) {
  const sorted = useMemo(
    () => [...rows].sort((a, b) => (b.weightPercent ?? 0) - (a.weightPercent ?? 0)),
    [rows],
  );
  return (
    <table className="w-full text-left text-sm tabular-nums">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-text-secondary">
          <th className="py-1 pr-4 font-medium">Ticker</th>
          <th className="hidden py-1 pr-4 font-medium lg:table-cell">Bank</th>
          <th className="hidden py-1 pr-4 font-medium md:table-cell">Account</th>
          <th className="py-1 pr-4 text-right font-medium">Shares</th>
          <th className="hidden py-1 pr-4 text-right font-medium md:table-cell">Price</th>
          <th className="py-1 pr-4 text-right font-medium">Value</th>
          <th className="py-1 pr-4 text-right font-medium">Total P&amp;L</th>
          <th className="hidden py-1 pr-4 text-right font-medium md:table-cell">Weight</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((p, i) => (
          <tr key={`${p.ticker}-${p.account}-${i}`} className="border-t border-border/60">
            <td className="py-1.5 pr-4 font-medium text-text-primary">
              {p.ticker}
              {p.companyName && <span className="ml-2 hidden text-xs text-text-secondary xl:inline">{p.companyName}</span>}
              {p.afterHours && <span className="ml-1.5 text-[10px] text-warning">AH</span>}
            </td>
            <td className="hidden py-1.5 pr-4 text-text-secondary lg:table-cell">{p.institution ?? "—"}</td>
            <td className="hidden py-1.5 pr-4 text-text-secondary md:table-cell">
              <AccountBadge account={p.account} currency={p.currency} />
            </td>
            <td className="py-1.5 pr-4 text-right text-text-primary">{num(p.shares, 0)}</td>
            <td className="hidden py-1.5 pr-4 text-right text-text-primary md:table-cell">{num(p.price)}</td>
            <td className="py-1.5 pr-4 text-right text-text-primary">
              {num(p.marketValue)} <span className="text-[10px] text-text-secondary">{p.currency}</span>
            </td>
            <td className={`py-1.5 pr-4 text-right ${pnlClass(p.totalPnl)}`}>{num(p.totalPnl)}</td>
            <td className="hidden py-1.5 pr-4 text-right text-text-secondary md:table-cell">{pct(p.weightPercent)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function GroupedTable({
  groups,
  expanded,
  setExpanded,
}: {
  groups: GroupedRow[];
  expanded: string | null;
  setExpanded: (t: string | null) => void;
}) {
  const sorted = useMemo(() => [...groups].sort((a, b) => b.cadValue - a.cadValue), [groups]);
  return (
    <table className="w-full text-left text-sm tabular-nums">
      <thead>
        <tr className="text-xs uppercase tracking-wide text-text-secondary">
          <th className="py-1 pr-4 font-medium">Ticker</th>
          <th className="hidden py-1 pr-4 font-medium md:table-cell">Accounts</th>
          <th className="py-1 pr-4 text-right font-medium">Shares</th>
          <th className="py-1 pr-4 text-right font-medium">Value (CAD)</th>
          <th className="py-1 pr-4 text-right font-medium">Total P&amp;L</th>
          <th className="hidden py-1 pr-4 text-right font-medium md:table-cell">Weight</th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((g) => {
          const open = expanded === g.ticker;
          const multi = g.accounts.length > 1;
          return (
            <FragmentRow key={g.ticker}>
              <tr
                onClick={() => multi && setExpanded(open ? null : g.ticker)}
                className={`border-t border-border/60 ${multi ? "cursor-pointer" : ""}`}
              >
                <td className="py-1.5 pr-4 font-medium text-text-primary">
                  {multi && <span className="mr-1.5 inline-block w-2 text-text-secondary">{open ? "▾" : "▸"}</span>}
                  {g.ticker}
                  {g.companyName && <span className="ml-2 hidden text-xs text-text-secondary xl:inline">{g.companyName}</span>}
                </td>
                <td className="hidden py-1.5 pr-4 text-text-secondary md:table-cell">
                  {g.accounts.length} account{g.accounts.length === 1 ? "" : "s"}
                </td>
                <td className="py-1.5 pr-4 text-right text-text-primary">{num(g.shares, 0)}</td>
                <td className="py-1.5 pr-4 text-right text-text-primary">{cad(g.cadValue)}</td>
                <td className={`py-1.5 pr-4 text-right ${pnlClass(g.cadPnl)}`}>{num(g.cadPnl)}</td>
                <td className="hidden py-1.5 pr-4 text-right text-text-secondary md:table-cell">{pct(g.weight)}</td>
              </tr>
              {open &&
                g.accounts.map((a, i) => (
                  <tr key={`${g.ticker}-acct-${i}`} className="bg-[var(--hover-wash)] text-xs">
                    <td className="py-1 pr-4 pl-7 text-text-secondary" colSpan={2}>
                      <span className="text-text-primary">{a.institution ?? "—"}</span> ·{" "}
                      <AccountBadge account={a.account} currency={a.currency} />
                    </td>
                    <td className="py-1 pr-4 text-right text-text-secondary">{num(a.shares, 0)}</td>
                    <td className="py-1 pr-4 text-right text-text-secondary">{num(a.cadMarketValue)}</td>
                    <td className={`py-1 pr-4 text-right ${pnlClass(a.cadPnl)}`}>{num(a.cadPnl)}</td>
                    <td className="hidden py-1 pr-4 text-right text-text-secondary md:table-cell">{pct(a.weightPercent)}</td>
                  </tr>
                ))}
            </FragmentRow>
          );
        })}
      </tbody>
    </table>
  );
}

function FragmentRow({ children }: { children: React.ReactNode }) {
  return <>{children}</>;
}

function AccountBadge({ account, currency }: { account: string | null; currency: string }) {
  if (!account) return <span className="text-text-secondary">{currency}</span>;
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="truncate">{account}</span>
    </span>
  );
}

function Filter({
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
      { ticker: p.ticker, companyName: p.companyName, shares: 0, cadValue: 0, cadPnl: 0, weight: 0, accounts: [] };
    g.shares += p.shares ?? 0;
    g.cadValue += p.cadMarketValue ?? 0;
    g.cadPnl += p.cadPnl ?? 0;
    g.weight += p.weightPercent ?? 0;
    if (!g.companyName && p.companyName) g.companyName = p.companyName;
    g.accounts.push(p);
    map.set(p.ticker, g);
  }
  return [...map.values()];
}
