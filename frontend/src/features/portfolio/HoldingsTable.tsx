"use client";

import { MotionCard } from "@/components/ui/MotionCard";
import {
  getCash,
  getPortfolioValue,
  setCash,
  type CashBalanceView,
  type PortfolioSnapshot,
  type PositionValue,
} from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { subscribeToTopic } from "@/lib/wsClient";
import { Fragment, useEffect, useMemo, useState } from "react";

const money = (n: number | null) =>
  n == null ? "—" : `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const qty = (n: number | null) => (n == null ? "—" : n.toLocaleString(undefined, { maximumFractionDigits: 4 }));
const signedMoney = (n: number) =>
  `${n < 0 ? "-" : ""}$${Math.abs(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const pnlClass = (n: number) => (n >= 0 ? "text-gains" : "text-losses");

type AccountGroup = {
  key: string;
  accountName: string;
  institution: string | null;
  currency: string; // "CAD" | "USD"
  ownerType: string | null;
  ownerName: string | null;
  accountType: string | null; // "TFSA" | "RRSP" | "Cash" | "Corporate" | … (null when unknown)
  positions: PositionValue[];
  cash: CashBalanceView[];
};

type OwnerGroup = {
  key: string;
  ownerType: string | null;
  ownerName: string | null;
  accounts: AccountGroup[];
};

/** One (owner + account type) rollup — the same registration combined across banks, e.g. an owner's
 * NBDB TFSA + RBC TFSA. All amounts are CAD sums over the member accounts. Securities and cash are
 * kept separate: invested/gain-loss cover securities only (cash has no cost or P&L). */
type TypeRollup = {
  key: string;
  ownerType: string | null;
  ownerName: string | null;
  accountType: string;
  institutions: string[];
  accounts: AccountGroup[];
  invested: number; // Σ CAD ACB (book cost of securities)
  marketCad: number; // Σ CAD market value of securities
  cashCad: number; // Σ CAD uninvested cash
  pnlCad: number; // marketCad − invested (securities P&L)
};

/**
 * The Holdings ledger (Story 3.5 + Multi-Bank Holdings). Live from `/topic/portfolio`.
 *
 * Information architecture (redesigned — the flat per-owner mega-table made it hard to tell which
 * account was on screen while scrolling, and cash/rollup/detail repeated the same numbers three
 * times): a compact cross-bank summary up top, an owner switcher so the household's four ledgers
 * don't have to be read as one continuous scroll, then each owner as a distinct panel containing one
 * clearly-bordered card per account (cash shown as a plain stat, not a disguised fake holding row).
 */
export function HoldingsTable() {
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);
  const [cash, setCashRows] = useState<CashBalanceView[]>([]);
  const [selectedOwner, setSelectedOwner] = useState<string>("all");

  const refetchCash = () => getCash().then(setCashRows).catch(() => {});

  useEffect(() => {
    let active = true;
    getPortfolioValue().then((s) => active && setSnap(s)).catch(() => {});
    refetchCash();
    const handle = subscribeToTopic<PortfolioSnapshot>("/topic/portfolio", (s) => setSnap(s));
    return () => {
      active = false;
      handle.disconnect();
    };
  }, []);

  const positions = useMemo(() => snap?.positions ?? [], [snap]);

  // USD→CAD rate derived from any priced USD position (for showing USD cash in CAD, and vice versa).
  const fx = useMemo(() => {
    const p = positions.find((x) => x.currency === "USD" && x.usdMarketValue && x.cadMarketValue);
    return p && p.usdMarketValue ? (p.cadMarketValue ?? 0) / p.usdMarketValue : 1.42;
  }, [positions]);

  const owners = useMemo(() => groupByOwner(positions, cash), [positions, cash]);
  const visibleOwners = useMemo(
    () => (selectedOwner === "all" ? owners : owners.filter((o) => o.key === selectedOwner)),
    [owners, selectedOwner],
  );

  async function removeCash(c: CashBalanceView) {
    await setCash(c.account, c.currency, 0);
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

  const totalCad = snap?.totalValueCad ?? null;
  const totalUsd = snap?.totalValueUsd ?? null;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-medium text-text-primary">Holdings by account</h3>
        <span className="text-xs text-text-secondary tabular-nums">
          Total {money(totalCad)} CAD{totalUsd != null && ` · ≈ ${money(totalUsd)} USD`}
        </span>
      </div>

      <OwnerTabs owners={owners} selected={selectedOwner} onSelect={setSelectedOwner} />

      <AccountTypeRollup owners={visibleOwners} fx={fx} />

      <div className="flex flex-col gap-4">
        {visibleOwners.map((o, i) => (
          <OwnerPanel key={o.key} owner={o} fx={fx} index={i} onRemoveCash={removeCash} />
        ))}
      </div>
    </div>
  );
}

/**
 * Owner switcher — the single biggest fix for "which account am I looking at": instead of every
 * owner's full ledger stacked in one endless scroll, pick one household member (or "All") and only
 * their panel renders below. Also scopes the cross-bank rollup, so one control filters everything.
 */
function OwnerTabs({
  owners,
  selected,
  onSelect,
}: {
  owners: OwnerGroup[];
  selected: string;
  onSelect: (key: string) => void;
}) {
  if (owners.length <= 1) return null;
  return (
    <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Filter by account owner">
      <TabButton active={selected === "all"} onClick={() => onSelect("all")}>
        All owners
      </TabButton>
      {owners.map((o) => {
        const isJoint = (o.ownerType ?? "").toLowerCase() === "joint";
        return (
          <TabButton key={o.key} active={selected === o.key} onClick={() => onSelect(o.key)} accent={isJoint}>
            {o.ownerName ?? "Unassigned"}
            <span className="ml-1 opacity-60">({o.accounts.length})</span>
          </TabButton>
        );
      })}
    </div>
  );
}

function TabButton({
  active,
  accent,
  onClick,
  children,
}: {
  active: boolean;
  accent?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={cn(
        "min-h-[36px] rounded-full border px-3.5 py-1.5 text-[13px] font-medium transition-colors",
        active
          ? accent
            ? "border-accent/50 bg-accent/15 text-accent"
            : "border-text-primary/25 bg-elevated text-text-primary"
          : "border-border text-text-secondary hover:border-border hover:bg-[var(--hover-wash)] hover:text-text-primary",
      )}
    >
      {children}
    </button>
  );
}

/**
 * Combined-by-account-type rollup (Multi-Bank Holdings). For each owner, the same registration type
 * is summed ACROSS banks — e.g. an owner's NBDB TFSA + RBC TFSA shown as one TFSA line with total
 * invested and gain/loss (CAD). Owners never merge (a corporation stays its own owner), so nothing
 * crosses an ownership boundary. Each type row expands to the underlying per-bank accounts.
 */
function AccountTypeRollup({ owners, fx }: { owners: OwnerGroup[]; fx: number }) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const toggle = (key: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const ownerRollups = owners
    .map((o) => ({ owner: o, types: rollupTypes(o, fx) }))
    .filter((r) => r.types.length > 0);
  if (ownerRollups.length === 0) return null;

  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-text-primary">Summary — combined by account type</h4>
      <p className="text-xs text-text-secondary">
        Same registration summed across banks, within each owner (in CAD). Invested and gain/loss cover
        securities; uninvested cash is shown separately.
      </p>
      <div className="overflow-x-auto rounded-xl border border-border">
        <table className="w-full min-w-[720px] text-left text-sm tabular-nums">
          <thead>
            <tr className="text-[11px] uppercase tracking-wide text-text-secondary">
              <th className="px-3 py-2 font-medium">Owner / Type</th>
              <th className="hidden px-3 py-2 font-medium sm:table-cell">Banks</th>
              <th className="px-3 py-2 text-right font-medium">Invested ≈CAD</th>
              <th className="px-3 py-2 text-right font-medium">Market ≈CAD</th>
              <th className="px-3 py-2 text-right font-medium">Cash ≈CAD</th>
              <th className="px-3 py-2 text-right font-medium">Gain / Loss</th>
            </tr>
          </thead>
          {ownerRollups.map(({ owner, types }) => (
            <tbody key={owner.key}>
              <tr className="border-t border-border bg-[var(--hover-wash)]">
                <td className="px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-text-secondary" colSpan={6}>
                  {owner.ownerType ? `${owner.ownerType} · ` : ""}
                  {owner.ownerName ?? "Unassigned"}
                </td>
              </tr>
              {types.map((r) => {
                const pct = r.invested > 0 ? (r.pnlCad / r.invested) * 100 : null;
                const isOpen = expanded.has(r.key);
                const canExpand = r.accounts.length > 1 || r.institutions.length > 1;
                return (
                  <Fragment key={r.key}>
                    <tr
                      className={cn(
                        "border-t border-border/60",
                        canExpand && "cursor-pointer hover:bg-[var(--hover-wash)]",
                      )}
                      onClick={canExpand ? () => toggle(r.key) : undefined}
                    >
                      <td className="px-3 py-2 font-medium text-text-primary">
                        {canExpand && (
                          <span className="mr-1.5 inline-block w-2 text-text-secondary">{isOpen ? "▾" : "▸"}</span>
                        )}
                        {r.accountType}
                      </td>
                      <td className="hidden px-3 py-2 sm:table-cell">
                        <div className="flex flex-wrap gap-1">
                          {r.institutions.length > 0
                            ? r.institutions.map((inst) => (
                                <Pill key={inst} muted>
                                  {inst}
                                </Pill>
                              ))
                            : <span className="text-text-secondary">—</span>}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-right text-text-primary">{money(r.invested)}</td>
                      <td className="px-3 py-2 text-right text-text-primary">{money(r.marketCad)}</td>
                      <td className="px-3 py-2 text-right text-text-secondary">{r.cashCad > 0 ? money(r.cashCad) : "—"}</td>
                      <td className={`px-3 py-2 text-right font-medium ${pnlClass(r.pnlCad)}`}>
                        {signedMoney(r.pnlCad)}
                        {pct != null && <span className="ml-1 text-[11px]">({pct >= 0 ? "+" : ""}{pct.toFixed(2)}%)</span>}
                      </td>
                    </tr>
                    {isOpen &&
                      r.accounts.map((a) => {
                        const t = accountCad(a, fx);
                        const apct = t.invested > 0 ? (t.pnl / t.invested) * 100 : null;
                        return (
                          <tr key={a.key} className="border-t border-border/40 bg-surface text-[13px]">
                            <td className="px-3 py-1.5 pl-8 text-text-secondary">{a.accountName}</td>
                            <td className="hidden px-3 py-1.5 sm:table-cell">
                              {a.institution ? <Pill muted>{a.institution}</Pill> : <span className="text-text-secondary">—</span>}
                            </td>
                            <td className="px-3 py-1.5 text-right text-text-secondary">{money(t.invested)}</td>
                            <td className="px-3 py-1.5 text-right text-text-secondary">{money(t.market)}</td>
                            <td className="px-3 py-1.5 text-right text-text-secondary">{t.cash > 0 ? money(t.cash) : "—"}</td>
                            <td className={`px-3 py-1.5 text-right ${pnlClass(t.pnl)}`}>
                              {signedMoney(t.pnl)}
                              {apct != null && <span className="ml-1 text-[11px]">({apct >= 0 ? "+" : ""}{apct.toFixed(2)}%)</span>}
                            </td>
                          </tr>
                        );
                      })}
                  </Fragment>
                );
              })}
            </tbody>
          ))}
        </table>
      </div>
    </div>
  );
}

/**
 * One owner's full ledger — a distinct glass panel (the app's established grouping surface) so it
 * reads as one clear unit vs. the other owners, containing one AccountCard per account plus an
 * owner-level total. Replaces the old single continuous table that mixed every account's rows.
 */
function OwnerPanel({
  owner,
  fx,
  index,
  onRemoveCash,
}: {
  owner: OwnerGroup;
  fx: number;
  index: number;
  onRemoveCash: (c: CashBalanceView) => void;
}) {
  let ownerCad = 0;
  let ownerUsd = 0;
  for (const a of owner.accounts) {
    const t = accountTotals(a, fx);
    ownerCad += t.cad;
    ownerUsd += t.usd;
  }
  const isJoint = (owner.ownerType ?? "").toLowerCase() === "joint";

  return (
    <MotionCard index={index} interactive={false} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={cn(
              "rounded-full px-2.5 py-0.5 text-[11px] font-semibold",
              isJoint ? "bg-accent/15 text-accent" : "bg-elevated text-text-secondary",
            )}
          >
            {owner.ownerType ?? "Account"}
          </span>
          <h4 className="text-sm font-semibold text-text-primary">{owner.ownerName ?? "Unassigned"}</h4>
          <span className="text-xs text-text-secondary">
            {owner.accounts.length} account{owner.accounts.length === 1 ? "" : "s"}
          </span>
        </div>
        <div className="text-right">
          <div className="font-mono text-sm font-semibold tabular-nums text-text-primary">{money(ownerCad)} CAD</div>
          <div className="font-mono text-[11px] tabular-nums text-text-secondary">≈ {money(ownerUsd)} USD</div>
        </div>
      </div>

      <div className="flex flex-col gap-3">
        {owner.accounts.map((a) => (
          <AccountCard key={a.key} account={a} fx={fx} onRemoveCash={onRemoveCash} />
        ))}
      </div>
    </MotionCard>
  );
}

/**
 * One account, one unambiguous card: name/currency/bank in the header (impossible to lose track of
 * while scrolling), cash as a plain labelled stat (not a fake ticker row), then just that account's
 * holdings in a compact table. This — not a shared background tint on a table row — is what actually
 * fixes "which account am I looking at."
 */
function AccountCard({
  account,
  fx,
  onRemoveCash,
}: {
  account: AccountGroup;
  fx: number;
  onRemoveCash: (c: CashBalanceView) => void;
}) {
  const t = accountTotals(account, fx);
  const isCad = account.currency === "CAD";
  const sortedPositions = [...account.positions].sort(
    (a, b) => (b.cadMarketValue ?? 0) - (a.cadMarketValue ?? 0),
  );

  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1.5 border-b border-border bg-[var(--hover-wash)] px-3.5 py-2.5">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="font-semibold text-text-primary">{account.accountName}</span>
          <Pill>{account.currency}</Pill>
          {account.institution && <Pill muted>{account.institution}</Pill>}
        </div>
        <div className="text-right">
          <div className="font-mono text-[13px] font-semibold tabular-nums text-text-primary">
            {money(t.cad)} CAD
          </div>
          <div className="font-mono text-[11px] tabular-nums text-text-secondary">≈ {money(t.usd)} USD</div>
        </div>
      </div>

      {account.cash.map((c) => {
        const cad = c.currency === "CAD" ? c.amount : c.amount * fx;
        const usd = c.currency === "USD" ? c.amount : c.amount / fx;
        return (
          <div
            key={`cash-${c.id}`}
            className="flex flex-wrap items-center justify-between gap-2 border-b border-border/60 px-3.5 py-2 text-[13px]"
          >
            <span className="flex items-center gap-1.5 text-text-secondary">
              <span className="font-medium text-accent">Cash available</span>
              <button
                onClick={() => onRemoveCash(c)}
                className="min-h-[24px] text-[11px] text-losses hover:underline"
                aria-label={`Remove cash balance for ${account.accountName}`}
              >
                remove
              </button>
            </span>
            <span className="font-mono tabular-nums text-text-primary">
              {money(isCad ? cad : usd)} {account.currency}
              <span className="ml-1.5 text-text-secondary">
                ({money(cad)} CAD / {money(usd)} USD)
              </span>
            </span>
          </div>
        );
      })}

      {sortedPositions.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-[13px] tabular-nums">
            <thead>
              <tr className="text-[10px] uppercase tracking-wide text-text-secondary">
                <th className="px-3.5 py-1.5 font-medium">Symbol</th>
                <th className="hidden px-3.5 py-1.5 font-medium md:table-cell">Description</th>
                <th className="px-3.5 py-1.5 text-right font-medium">Qty</th>
                <th className="hidden px-3.5 py-1.5 text-right font-medium lg:table-cell">Book Value</th>
                <th className="px-3.5 py-1.5 text-right font-medium">Market Value</th>
                <th className="px-3.5 py-1.5 text-right font-medium">≈ CAD</th>
                <th className="px-3.5 py-1.5 text-right font-medium">≈ USD</th>
              </tr>
            </thead>
            <tbody>
              {sortedPositions.map((p) => (
                <tr key={p.id} className="border-t border-border/40">
                  <td className="px-3.5 py-1.5 font-medium text-text-primary">
                    {p.ticker}
                    {p.afterHours && <span className="ml-1.5 text-[10px] text-warning">AH</span>}
                  </td>
                  <td className="hidden px-3.5 py-1.5 text-text-secondary md:table-cell">
                    {p.companyName ?? "—"}
                    {p.currency === "USD" && isCad && p.price != null && (
                      <span className="ml-1.5 text-[11px] text-text-secondary/70">· US${qty(p.price)}</span>
                    )}
                  </td>
                  <td className="px-3.5 py-1.5 text-right text-text-primary">{qty(p.shares)}</td>
                  <td className="hidden px-3.5 py-1.5 text-right text-text-secondary lg:table-cell">{money(p.costBasis)}</td>
                  <td className="px-3.5 py-1.5 text-right text-text-primary">
                    {money(isCad ? p.cadMarketValue : p.usdMarketValue)}
                  </td>
                  <td className="px-3.5 py-1.5 text-right text-text-primary">{money(p.cadMarketValue)}</td>
                  <td className="px-3.5 py-1.5 text-right text-text-primary">{money(p.usdMarketValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Pill({ children, muted }: { children: React.ReactNode; muted?: boolean }) {
  return (
    <span
      className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${
        muted ? "bg-elevated text-text-secondary" : "bg-accent/10 text-accent"
      }`}
    >
      {children}
    </span>
  );
}

/** Sum a single account's positions + cash into native / CAD / USD totals. */
function accountTotals(a: AccountGroup, fx: number): { native: number; cad: number; usd: number } {
  let cad = 0;
  let usd = 0;
  for (const p of a.positions) {
    cad += p.cadMarketValue ?? 0;
    usd += p.usdMarketValue ?? 0;
  }
  for (const c of a.cash) {
    cad += c.currency === "CAD" ? c.amount : c.amount * fx;
    usd += c.currency === "USD" ? c.amount : c.amount / fx;
  }
  return { native: a.currency === "CAD" ? cad : usd, cad, usd };
}

/** CAD book cost / market value / gain-loss (securities) plus uninvested cash for one account. */
function accountCad(a: AccountGroup, fx: number): { invested: number; market: number; pnl: number; cash: number } {
  let invested = 0;
  let market = 0;
  let pnl = 0;
  for (const p of a.positions) {
    invested += p.cadAcb ?? 0;
    market += p.cadMarketValue ?? 0;
    pnl += p.cadPnl ?? 0;
  }
  let cash = 0;
  for (const c of a.cash) {
    cash += c.currency === "CAD" ? c.amount : c.amount * fx;
  }
  return { invested, market, pnl, cash };
}

/**
 * Combine an owner's accounts by registration type across banks — e.g. their NBDB TFSA + RBC TFSA
 * into a single TFSA rollup. Sums stay in CAD (the common currency) so cross-currency accounts (a
 * dual-currency RBC account's CAD + USD sides) add up. Accounts whose type is unknown fall into an
 * "Other" bucket. Sorted by market value (securities + cash), largest first.
 */
function rollupTypes(owner: OwnerGroup, fx: number): TypeRollup[] {
  const byType = new Map<string, TypeRollup>();
  for (const a of owner.accounts) {
    const type = a.accountType ?? "Other";
    const key = `${owner.key}|${type}`;
    let r = byType.get(key);
    if (!r) {
      r = {
        key,
        ownerType: owner.ownerType,
        ownerName: owner.ownerName,
        accountType: type,
        institutions: [],
        accounts: [],
        invested: 0,
        marketCad: 0,
        cashCad: 0,
        pnlCad: 0,
      };
      byType.set(key, r);
    }
    r.accounts.push(a);
    if (a.institution && !r.institutions.includes(a.institution)) r.institutions.push(a.institution);
    const t = accountCad(a, fx);
    r.invested += t.invested;
    r.marketCad += t.market;
    r.cashCad += t.cash;
    r.pnlCad += t.pnl;
  }
  return [...byType.values()].sort((x, y) => y.marketCad + y.cashCad - (x.marketCad + x.cashCad));
}

/** Build owner → account groups from live positions + cash. Joint owners sort first. */
function groupByOwner(positions: PositionValue[], cash: CashBalanceView[]): OwnerGroup[] {
  const accounts = new Map<string, AccountGroup>();

  const accountKey = (institution: string | null, account: string | null) =>
    `${institution ?? ""}|${account ?? ""}`;

  const ensureAccount = (
    institution: string | null,
    account: string | null,
    accountName: string | null,
    currency: string | null,
    ownerType: string | null,
    ownerName: string | null,
    accountType: string | null,
  ): AccountGroup => {
    const key = accountKey(institution, account);
    let g = accounts.get(key);
    if (!g) {
      g = {
        key,
        accountName: accountName ?? account ?? "Account",
        institution,
        currency: currency ?? "CAD",
        ownerType,
        ownerName,
        accountType,
        positions: [],
        cash: [],
      };
      accounts.set(key, g);
    }
    // Owner/name/type can arrive first from whichever row; keep the first non-null.
    if (!g.ownerName && ownerName) g.ownerName = ownerName;
    if (!g.ownerType && ownerType) g.ownerType = ownerType;
    if (!g.accountType && accountType) g.accountType = accountType;
    return g;
  };

  for (const p of positions) {
    ensureAccount(p.institution, p.account, p.accountName, p.accountCurrency, p.ownerType, p.ownerName, p.accountType).positions.push(p);
  }
  // Cash carries no institution; match an existing account by label, else create an institution-less one.
  for (const c of cash) {
    const existing = [...accounts.values()].find((g) => (g.accountName ?? "") === (c.accountName ?? c.account) || g.key.endsWith(`|${c.account}`));
    if (existing) {
      existing.cash.push(c);
      if (!existing.ownerName && c.ownerName) existing.ownerName = c.ownerName;
      if (!existing.ownerType && c.ownerType) existing.ownerType = c.ownerType;
    } else {
      ensureAccount(null, c.account, c.accountName, c.currency, c.ownerType, c.ownerName, null).cash.push(c);
    }
  }

  const owners = new Map<string, OwnerGroup>();
  for (const a of accounts.values()) {
    const oKey = a.ownerName ?? "Unassigned";
    let o = owners.get(oKey);
    if (!o) {
      o = { key: oKey, ownerType: a.ownerType, ownerName: a.ownerName, accounts: [] };
      owners.set(oKey, o);
    }
    if (!o.ownerType && a.ownerType) o.ownerType = a.ownerType;
    o.accounts.push(a);
  }

  const list = [...owners.values()];
  for (const o of list) o.accounts.sort((a, b) => a.accountName.localeCompare(b.accountName));
  list.sort((a, b) => {
    const ja = (a.ownerType ?? "").toLowerCase() === "joint" ? 0 : 1;
    const jb = (b.ownerType ?? "").toLowerCase() === "joint" ? 0 : 1;
    if (ja !== jb) return ja - jb;
    return (a.ownerName ?? "~").localeCompare(b.ownerName ?? "~");
  });
  return list;
}
