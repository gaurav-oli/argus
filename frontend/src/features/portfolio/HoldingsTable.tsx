"use client";

import {
  getCash,
  getPortfolioValue,
  setCash,
  type CashBalanceView,
  type PortfolioSnapshot,
  type PositionValue,
} from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { Fragment, useEffect, useMemo, useState } from "react";

const money = (n: number | null) =>
  n == null ? "—" : `$${n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const qty = (n: number | null) => (n == null ? "—" : n.toLocaleString(undefined, { maximumFractionDigits: 4 }));

/** "Joint · Gaurav & Varsha" / "Solo · Gaurav Oli" / null when unknown. */
const ownerLabel = (ownerType: string | null, ownerName: string | null): string | null => {
  if (!ownerName && !ownerType) return null;
  if (ownerName && ownerType) return `${ownerType} · ${ownerName}`;
  return ownerName ?? ownerType;
};

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
 * The Holdings ledger (Story 3.5 + Multi-Bank Holdings). Live from `/topic/portfolio`. Grouped the
 * way a household reads a statement: by owner (Joint / each Solo holder) → account → position, with
 * per-account and per-owner subtotals, native + CAD + USD columns, and uninvested cash folded in so
 * each account totals to the brokerage's figure. Works across banks — every account shows which bank
 * it's at, so uploading another institution's statement slots straight in.
 */
export function HoldingsTable() {
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(null);
  const [cash, setCashRows] = useState<CashBalanceView[]>([]);

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

      <AccountTypeRollup owners={owners} fx={fx} />

      {owners.map((o) => (
        <OwnerSection key={o.key} owner={o} fx={fx} onRemoveCash={removeCash} />
      ))}

      <CashSummary cash={cash} fx={fx} onRemove={removeCash} />
    </div>
  );
}

const signedMoney = (n: number) => `${n < 0 ? "-" : ""}$${Math.abs(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const pnlClass = (n: number) => (n >= 0 ? "text-gains" : "text-losses");

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
      <h4 className="text-sm font-semibold text-text-primary">Combined by account type</h4>
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
                      className={`border-t border-border/60 ${canExpand ? "cursor-pointer hover:bg-[var(--hover-wash)]" : ""}`}
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

/** One owner (Joint or a Solo holder): a badge, then each of their accounts, then an owner total. */
function OwnerSection({
  owner,
  fx,
  onRemoveCash,
}: {
  owner: OwnerGroup;
  fx: number;
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
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`rounded-full px-2.5 py-0.5 text-[11px] font-semibold ${
            isJoint ? "bg-accent/15 text-accent" : "bg-elevated text-text-secondary"
          }`}
        >
          {owner.ownerType ?? "Account"}
        </span>
        <h4 className="text-sm font-semibold text-text-primary">{owner.ownerName ?? "Unassigned"}</h4>
      </div>

      <div className="overflow-x-auto rounded-xl border border-border">
        <table className="w-full min-w-[640px] text-left text-sm tabular-nums">
          <thead>
            <tr className="text-[11px] uppercase tracking-wide text-text-secondary">
              <th className="px-3 py-2 font-medium">Symbol</th>
              <th className="hidden px-3 py-2 font-medium md:table-cell">Description</th>
              <th className="px-3 py-2 text-right font-medium">Qty</th>
              <th className="hidden px-3 py-2 text-right font-medium lg:table-cell">Book Value</th>
              <th className="px-3 py-2 text-right font-medium">Market Value</th>
              <th className="px-3 py-2 text-right font-medium">≈ CAD</th>
              <th className="px-3 py-2 text-right font-medium">≈ USD</th>
            </tr>
          </thead>
          <tbody>
            {owner.accounts.map((a) => (
              <AccountRows key={a.key} account={a} fx={fx} onRemoveCash={onRemoveCash} />
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-accent/40 bg-accent/5 font-semibold text-text-primary">
              <td className="px-3 py-2" colSpan={4}>
                {owner.ownerType ? `${owner.ownerType} · ` : ""}
                {owner.ownerName ?? "Unassigned"} total
              </td>
              <td className="px-3 py-2 text-right" />
              <td className="px-3 py-2 text-right">{money(ownerCad)}</td>
              <td className="px-3 py-2 text-right">{money(ownerUsd)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}

/** An account sub-header, its cash row, its holdings, and an account subtotal. */
function AccountRows({
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
    <>
      <tr className="border-t border-border bg-[var(--hover-wash)]">
        <td className="px-3 py-2" colSpan={7}>
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-semibold text-text-primary">{account.accountName}</span>
            <Pill>{account.currency}</Pill>
            {account.institution && <Pill muted>{account.institution}</Pill>}
            {ownerLabel(account.ownerType, account.ownerName) && (
              <span className="text-[11px] text-text-secondary">
                {ownerLabel(account.ownerType, account.ownerName)}
              </span>
            )}
          </div>
        </td>
      </tr>

      {account.cash.map((c) => {
        const cad = c.currency === "CAD" ? c.amount : c.amount * fx;
        const usd = c.currency === "USD" ? c.amount : c.amount / fx;
        return (
          <tr key={`cash-${c.id}`} className="border-t border-border/60">
            <td className="px-3 py-1.5 font-medium text-accent">CASH</td>
            <td className="hidden px-3 py-1.5 text-text-secondary md:table-cell">
              Cash balance
              <button onClick={() => onRemoveCash(c)} className="ml-2 text-[10px] text-losses hover:underline">
                remove
              </button>
            </td>
            <td className="px-3 py-1.5 text-right text-text-secondary">—</td>
            <td className="hidden px-3 py-1.5 text-right text-text-secondary lg:table-cell">—</td>
            <td className="px-3 py-1.5 text-right text-text-primary">{money(isCad ? cad : usd)}</td>
            <td className="px-3 py-1.5 text-right text-text-primary">{money(cad)}</td>
            <td className="px-3 py-1.5 text-right text-text-primary">{money(usd)}</td>
          </tr>
        );
      })}

      {sortedPositions.map((p) => (
        <tr key={p.id} className="border-t border-border/60">
          <td className="px-3 py-1.5 font-medium text-text-primary">
            {p.ticker}
            {p.afterHours && <span className="ml-1.5 text-[10px] text-warning">AH</span>}
          </td>
          <td className="hidden px-3 py-1.5 text-text-secondary md:table-cell">
            {p.companyName ?? "—"}
            {p.currency === "USD" && isCad && p.price != null && (
              <span className="ml-1.5 text-[11px] text-text-secondary/70">· US${qty(p.price)}</span>
            )}
          </td>
          <td className="px-3 py-1.5 text-right text-text-primary">{qty(p.shares)}</td>
          <td className="hidden px-3 py-1.5 text-right text-text-secondary lg:table-cell">{money(p.costBasis)}</td>
          <td className="px-3 py-1.5 text-right text-text-primary">
            {money(isCad ? p.cadMarketValue : p.usdMarketValue)}
          </td>
          <td className="px-3 py-1.5 text-right text-text-primary">{money(p.cadMarketValue)}</td>
          <td className="px-3 py-1.5 text-right text-text-primary">{money(p.usdMarketValue)}</td>
        </tr>
      ))}

      <tr className="border-t border-border bg-surface font-medium">
        <td className="px-3 py-1.5 text-text-secondary" colSpan={4}>
          Subtotal · {account.accountName}
        </td>
        <td className="px-3 py-1.5 text-right text-text-primary">{money(t.native)}</td>
        <td className="px-3 py-1.5 text-right text-text-primary">{money(t.cad)}</td>
        <td className="px-3 py-1.5 text-right text-text-primary">{money(t.usd)}</td>
      </tr>
    </>
  );
}

/** Compact "cash on hand" summary across every account (req: which account, CAD/USD, owner). */
function CashSummary({
  cash,
  fx,
  onRemove,
}: {
  cash: CashBalanceView[];
  fx: number;
  onRemove: (c: CashBalanceView) => void;
}) {
  if (cash.length === 0) return null;
  let totCad = 0;
  let totUsd = 0;
  for (const c of cash) {
    totCad += c.currency === "CAD" ? c.amount : c.amount * fx;
    totUsd += c.currency === "USD" ? c.amount : c.amount / fx;
  }
  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-sm font-semibold text-text-primary">Cash on hand — by account</h4>
      <div className="overflow-x-auto rounded-xl border border-border">
        <table className="w-full min-w-[560px] text-left text-sm tabular-nums">
          <thead>
            <tr className="text-[11px] uppercase tracking-wide text-text-secondary">
              <th className="px-3 py-2 font-medium">Account</th>
              <th className="hidden px-3 py-2 font-medium md:table-cell">Owner</th>
              <th className="px-3 py-2 text-right font-medium">Cur.</th>
              <th className="px-3 py-2 text-right font-medium">Cash</th>
              <th className="px-3 py-2 text-right font-medium">≈ CAD</th>
              <th className="px-3 py-2 text-right font-medium">≈ USD</th>
            </tr>
          </thead>
          <tbody>
            {cash.map((c) => {
              const cad = c.currency === "CAD" ? c.amount : c.amount * fx;
              const usd = c.currency === "USD" ? c.amount : c.amount / fx;
              return (
                <tr key={c.id} className="border-t border-border/60">
                  <td className="px-3 py-1.5 font-medium text-text-primary">
                    {c.accountName ?? c.account}
                    <button onClick={() => onRemove(c)} className="ml-2 text-[10px] text-losses hover:underline">
                      remove
                    </button>
                  </td>
                  <td className="hidden px-3 py-1.5 text-text-secondary md:table-cell">
                    {ownerLabel(c.ownerType, c.ownerName) ?? "—"}
                  </td>
                  <td className="px-3 py-1.5 text-right">
                    <Pill>{c.currency}</Pill>
                  </td>
                  <td className="px-3 py-1.5 text-right text-text-primary">{money(c.amount)}</td>
                  <td className="px-3 py-1.5 text-right text-text-primary">{money(cad)}</td>
                  <td className="px-3 py-1.5 text-right text-text-primary">{money(usd)}</td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-accent/40 bg-accent/5 font-semibold text-text-primary">
              <td className="px-3 py-2" colSpan={4}>
                Total cash
              </td>
              <td className="px-3 py-2 text-right">{money(totCad)}</td>
              <td className="px-3 py-2 text-right">{money(totUsd)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
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
