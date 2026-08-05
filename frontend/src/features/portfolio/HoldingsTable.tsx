"use client";

import { BankIcon } from "@/components/ui/BankIcon";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import {
  getCash,
  getPortfolioValue,
  setCash,
  type CashBalanceView,
  type PortfolioSnapshot,
  type PositionValue,
} from "@/lib/apiClient";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
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
 * The Holdings ledger (Story 3.5 + Multi-Bank Holdings, redesigned to cut the redundancy of showing
 * every position at three simultaneous levels of detail). One table: account type is the resting
 * state — a handful of rows in CAD — and clicking a row is the only way to reach the underlying
 * accounts and their positions. Nothing renders pre-expanded, so nothing competes for attention with
 * everything else.
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

  const logos = useCompanyLogos(useMemo(() => positions.map((p) => p.ticker), [positions]));

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
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-medium text-text-primary">Holdings by account</h3>
        <span className="text-xs text-text-secondary tabular-nums">
          Total {money(totalCad)} CAD{totalUsd != null && ` · ≈ ${money(totalUsd)} USD`}
        </span>
      </div>

      <OwnerTabs owners={owners} selected={selectedOwner} onSelect={setSelectedOwner} />

      <Ledger owners={visibleOwners} fx={fx} logos={logos} onRemoveCash={removeCash} />
    </div>
  );
}

/**
 * Owner switcher — instead of every owner's full ledger stacked in one endless scroll, pick one
 * household member (or "All") and only their rows render below.
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
 * The one ledger table (replaces the old always-expanded rollup + separate per-owner cards). Each
 * (owner, account type) row is the resting state — invested/market/cash/gain-loss summed across
 * banks. Expanding it is the only way to reach the underlying accounts, and each account's cash and
 * positions render inline right there, so nothing is ever shown at two levels of detail at once.
 */
function Ledger({
  owners,
  fx,
  logos,
  onRemoveCash,
}: {
  owners: OwnerGroup[];
  fx: number;
  logos: Record<string, string>;
  onRemoveCash: (c: CashBalanceView) => void;
}) {
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
    <div className="overflow-x-auto rounded-xl border border-border">
      <table className="w-full min-w-[720px] text-left text-sm tabular-nums">
        <thead>
          <tr className="text-[11px] uppercase tracking-wide text-text-secondary">
            <th className="px-3 py-2 font-medium">Account type</th>
            <th className="hidden px-3 py-2 font-medium sm:table-cell">Banks</th>
            <th className="px-3 py-2 text-right font-medium">Invested ≈CAD</th>
            <th className="px-3 py-2 text-right font-medium">Market ≈CAD</th>
            <th className="px-3 py-2 text-right font-medium">Cash ≈CAD</th>
            <th className="px-3 py-2 text-right font-medium">Gain / Loss</th>
          </tr>
        </thead>
        {ownerRollups.map(({ owner, types }) => (
          <tbody key={owner.key}>
            {ownerRollups.length > 1 || owners.length > 1 ? (
              <tr className="border-t border-border bg-[var(--hover-wash)]">
                <td className="px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-text-secondary" colSpan={6}>
                  {owner.ownerType ? `${owner.ownerType} · ` : ""}
                  {owner.ownerName ?? "Unassigned"}
                </td>
              </tr>
            ) : null}
            {types.map((r) => {
              const pct = r.invested > 0 ? (r.pnlCad / r.invested) * 100 : null;
              const isOpen = expanded.has(r.key);
              return (
                <Fragment key={r.key}>
                  <tr
                    className="cursor-pointer border-t border-border/60 hover:bg-[var(--hover-wash)]"
                    onClick={() => toggle(r.key)}
                  >
                    <td className="px-3 py-3 font-medium text-text-primary">
                      <span className="mr-1.5 inline-block w-2 text-text-secondary">{isOpen ? "▾" : "▸"}</span>
                      {r.accountType}
                      <span className="ml-2 text-xs font-normal text-text-secondary">
                        {r.accounts.length} account{r.accounts.length === 1 ? "" : "s"}
                      </span>
                    </td>
                    <td className="hidden px-3 py-3 sm:table-cell">
                      <div className="flex flex-wrap gap-1">
                        {r.institutions.length > 0
                          ? r.institutions.map((inst) => (
                              <Pill key={inst} muted icon={<BankIcon institution={inst} size={12} />}>
                                {inst}
                              </Pill>
                            ))
                          : <span className="text-text-secondary">—</span>}
                      </div>
                    </td>
                    <td className="px-3 py-3 text-right text-text-primary">{money(r.invested)}</td>
                    <td className="px-3 py-3 text-right text-text-primary">{money(r.marketCad)}</td>
                    <td className="px-3 py-3 text-right text-text-secondary">{r.cashCad > 0 ? money(r.cashCad) : "—"}</td>
                    <td className={`px-3 py-3 text-right font-medium ${pnlClass(r.pnlCad)}`}>
                      {signedMoney(r.pnlCad)}
                      {pct != null && <span className="ml-1 text-[11px]">({pct >= 0 ? "+" : ""}{pct.toFixed(2)}%)</span>}
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="border-t border-border/40 bg-surface">
                      <td colSpan={6} className="px-3 pb-5 pl-8 pt-1">
                        <div className="flex flex-col gap-5">
                          {r.accounts.map((a) => (
                            <AccountDetail
                              key={a.key}
                              account={a}
                              fx={fx}
                              logos={logos}
                              onRemoveCash={onRemoveCash}
                              showAccountSummary={r.accounts.length > 1}
                            />
                          ))}
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        ))}
      </table>
    </div>
  );
}

/**
 * One account's detail, inline inside an expanded ledger row: an optional summary line (skipped when
 * a type has only one account — the parent row already shows those totals), cash as a plain labelled
 * stat with a remove action, then just that account's positions.
 */
function AccountDetail({
  account,
  fx,
  logos,
  onRemoveCash,
  showAccountSummary,
}: {
  account: AccountGroup;
  fx: number;
  logos: Record<string, string>;
  onRemoveCash: (c: CashBalanceView) => void;
  showAccountSummary: boolean;
}) {
  const t = accountTotals(account, fx);
  const cadT = accountCad(account, fx);
  const apct = cadT.invested > 0 ? (cadT.pnl / cadT.invested) * 100 : null;
  const isCad = account.currency === "CAD";
  const sortedPositions = [...account.positions].sort(
    (a, b) => (b.cadMarketValue ?? 0) - (a.cadMarketValue ?? 0),
  );

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span className="text-[13px] font-semibold text-text-primary">{account.accountName}</span>
          <Pill>{account.currency}</Pill>
          {account.institution && (
            <Pill muted icon={<BankIcon institution={account.institution} size={12} />}>
              {account.institution}
            </Pill>
          )}
        </div>
        {showAccountSummary ? (
          <div className="flex items-center gap-4 text-xs">
            <span className="text-text-secondary">
              Invested {money(cadT.invested)} · Market {money(cadT.market)}
            </span>
            <span className={`font-medium ${pnlClass(cadT.pnl)}`}>
              {signedMoney(cadT.pnl)}
              {apct != null && <span className="ml-1 text-[11px]">({apct >= 0 ? "+" : ""}{apct.toFixed(2)}%)</span>}
            </span>
          </div>
        ) : (
          <div className="text-right">
            <div className="font-mono text-[13px] font-semibold tabular-nums text-text-primary">
              {money(t.cad)} CAD
            </div>
            <div className="font-mono text-[11px] tabular-nums text-text-secondary">≈ {money(t.usd)} USD</div>
          </div>
        )}
      </div>

      {account.cash.map((c) => {
        const cad = c.currency === "CAD" ? c.amount : c.amount * fx;
        const usd = c.currency === "USD" ? c.amount : c.amount / fx;
        return (
          <div
            key={`cash-${c.id}`}
            className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-[var(--hover-wash)] px-3 py-2 text-[13px]"
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
        <div className="overflow-x-auto rounded-lg border border-border/60">
          <table className="w-full text-left text-[13px] tabular-nums">
            <thead>
              <tr className="text-[10px] uppercase tracking-wide text-text-secondary">
                <th className="px-3 py-1.5 font-medium">Symbol</th>
                <th className="hidden px-3 py-1.5 font-medium md:table-cell">Description</th>
                <th className="px-3 py-1.5 text-right font-medium">Qty</th>
                <th className="hidden px-3 py-1.5 text-right font-medium lg:table-cell">Book Value</th>
                <th className="px-3 py-1.5 text-right font-medium">Market Value</th>
                <th className="px-3 py-1.5 text-right font-medium">≈ CAD</th>
                <th className="px-3 py-1.5 text-right font-medium">≈ USD</th>
              </tr>
            </thead>
            <tbody>
              {sortedPositions.map((p) => (
                <tr key={p.id} className="border-t border-border/40">
                  <td className="px-3 py-1.5 font-medium text-text-primary">
                    <span className="flex items-center gap-2">
                      <CompanyIcon
                        ticker={p.ticker}
                        logoUrl={logos[p.ticker]}
                        title={p.companyName ?? p.ticker}
                        size={20}
                      />
                      {p.ticker}
                      {p.afterHours && <span className="text-[10px] text-warning">AH</span>}
                    </span>
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
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Pill({ children, muted, icon }: { children: React.ReactNode; muted?: boolean; icon?: React.ReactNode }) {
  return (
    <span
      className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-semibold ${
        muted ? "bg-elevated text-text-secondary" : "bg-accent/10 text-accent"
      }`}
    >
      {icon}
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
