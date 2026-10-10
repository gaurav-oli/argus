"use client";

import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { MotionCard } from "@/components/ui/MotionCard";
import { Sensitive } from "@/features/privacy/Sensitive";
import { getTradeLedger, type LedgerRow } from "@/lib/apiClient";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { useAutoRefresh } from "@/lib/useAutoRefresh";
import { cn } from "@/lib/utils";
import { Fragment, useMemo, useState } from "react";
import { LessonForTrade } from "./TradeLessons";
import { FilterChips, Pager, SortHeader, compareBy, usePaged, useSort } from "./tableKit";

const PAGE = 15;

type SystemFilter = "CURRENT" | "OLD" | "all";
type StatusFilter = "all" | "OPEN" | "CLOSED";
type ResultFilter = "all" | "won" | "lost";
type Key = "ticker" | "opened" | "closed" | "held" | "return" | "pnl";

const REASON: Record<string, string> = {
  HORIZON: "held to term",
  STOP: "stop hit",
  TRAILING_STOP: "trailing stop",
  TAKE_PROFIT: "took profit",
  THESIS_FLIP: "Agent 11 turned",
  THESIS_DECAY: "call reversed",
};

function day(iso: string | null): string {
  return iso ? new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "2-digit" }) : "—";
}

function px(n: number | null): string {
  return n == null ? "—" : n >= 100 ? n.toFixed(2) : n.toFixed(n >= 1 ? 2 : 4);
}

function signed(n: number | null, digits = 2): string {
  return n == null ? "—" : `${n >= 0 ? "+" : ""}${n.toFixed(digits)}`;
}

/** The return that matters for the row: realized when closed, unrealized (live) when open. */
function effectiveReturn(r: LedgerRow): number | null {
  return r.status === "CLOSED" ? r.returnPct : r.unrealizedPct;
}

/**
 * The Investor's trade journal: every paper trade, buy to sell — when it opened and at what price, how many shares for
 * how much, when, at what price and why it closed, how long it was held, and what it made. Open trades show the live
 * price and the unrealized result. Current system by default (the old system's trades stay one click away), sortable,
 * filterable, and downloadable as CSV.
 */
export function TradeLedger() {
  const [rows, setRows] = useState<LedgerRow[] | null>(null);
  const [system, setSystem] = useState<SystemFilter>("CURRENT");
  const [status, setStatus] = useState<StatusFilter>("all");
  const [result, setResult] = useState<ResultFilter>("all");
  const [sort, onSort] = useSort<Key>({ key: "opened", dir: "desc" });
  const [open, setOpen] = useState<number | null>(null);

  useAutoRefresh(() =>
    getTradeLedger()
      .then(setRows)
      .catch(() => setRows((prev) => prev ?? [])),
  );

  const logos = useCompanyLogos(useMemo(() => [...new Set((rows ?? []).map((r) => r.ticker))], [rows]));

  const inSystem = useMemo(() => (rows ?? []).filter((r) => system === "all" || r.system === system), [rows, system]);
  const visible = useMemo(() => {
    const keep = inSystem.filter(
      (r) =>
        (status === "all" || r.status === status) &&
        (result === "all" || (r.status === "CLOSED" && (result === "won" ? r.won === true : r.won === false))),
    );
    const get = {
      ticker: (r: LedgerRow) => r.ticker,
      opened: (r: LedgerRow) => r.openedAt,
      closed: (r: LedgerRow) => r.closedAt,
      held: (r: LedgerRow) => r.heldDays,
      return: (r: LedgerRow) => effectiveReturn(r),
      pnl: (r: LedgerRow) => r.pnl,
    }[sort.key];
    return [...keep].sort(compareBy(get, sort.dir));
  }, [inSystem, status, result, sort]);
  const paged = usePaged(visible, PAGE);

  if (!rows) return null;

  const closed = inSystem.filter((r) => r.status === "CLOSED");
  const wins = closed.filter((r) => r.won).length;
  const pnl = closed.reduce((s, r) => s + (r.pnl ?? 0), 0);
  const count = (pred: (r: LedgerRow) => boolean) => inSystem.filter(pred).length;

  function downloadCsv() {
    const head = [
      "Ticker", "Side", "System", "Status", "Opened", "Buy price", "Shares", "Amount", "Closed", "Sell price",
      "Why closed", "Days held", "Return %", "P&L $", "vs S&P 500 %", "Result",
    ];
    const lines = visible.map((r) =>
      [
        r.ticker, r.direction === "BULLISH" ? "Long" : "Short", r.system, r.status, r.openedAt, r.entryPrice, r.shares, r.amount,
        r.closedAt ?? "", r.exitPrice ?? "", r.exitReason ? (REASON[r.exitReason] ?? r.exitReason) : "", r.heldDays ?? "",
        r.returnPct ?? "", r.pnl ?? "", r.vsSpyPct ?? "", r.status === "OPEN" ? "open" : r.won ? "won" : "lost",
      ]
        .map((v) => `"${String(v).replace(/"/g, '""')}"`)
        .join(","),
    );
    const blob = new Blob([[head.join(","), ...lines].join("\n")], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `argus-trade-journal-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">Trade journal</h3>
          <p className="mt-0.5 text-xs text-text-secondary">
            Every paper trade, buy to sell — pretend money ($100 a trade), never your real portfolio.
          </p>
        </div>
        <button
          type="button"
          onClick={downloadCsv}
          className="shrink-0 border border-[var(--glass-border)] px-2.5 py-1 font-mono text-[11px] text-text-secondary transition-colors hover:border-accent hover:text-accent"
        >
          Download CSV
        </button>
      </div>

      <p className="font-mono text-xs text-text-secondary">
        {inSystem.length} trades · {closed.length} closed ·{" "}
        <span className="text-gains">{wins} won</span> · <span className="text-losses">{closed.length - wins} lost</span> · realized{" "}
        <Sensitive className="text-xs">
          <span style={{ color: pnl >= 0 ? "var(--color-gains)" : "var(--color-losses)" }}>${signed(pnl)}</span>
        </Sensitive>
      </p>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <FilterChips
          label="System"
          value={system}
          onChange={setSystem}
          options={[
            { value: "CURRENT", label: "Current system", count: (rows ?? []).filter((r) => r.system === "CURRENT").length },
            { value: "OLD", label: "Old system", count: (rows ?? []).filter((r) => r.system === "OLD").length },
            { value: "all", label: "All" },
          ]}
        />
        <FilterChips
          label="Status"
          value={status}
          onChange={setStatus}
          options={[
            { value: "all", label: "Any" },
            { value: "OPEN", label: "Open", count: count((r) => r.status === "OPEN") },
            { value: "CLOSED", label: "Closed", count: closed.length },
          ]}
        />
        <FilterChips
          label="Result"
          value={result}
          onChange={setResult}
          options={[
            { value: "all", label: "Any result" },
            { value: "won", label: "Won", count: wins },
            { value: "lost", label: "Lost", count: closed.length - wins },
          ]}
        />
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[60rem] font-mono text-[11px] tabular-nums">
          <thead className="border-b border-[var(--hairline)] text-left text-[10px]">
            <tr>
              <SortHeader label="Stock" k="ticker" sort={sort} onSort={onSort} />
              <SortHeader label="Bought" k="opened" sort={sort} onSort={onSort} />
              <th className="py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Buy price</th>
              <th className="py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Qty</th>
              <th className="py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Amount</th>
              <SortHeader label="Sold" k="closed" sort={sort} onSort={onSort} />
              <th className="py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Sell price</th>
              <th className="py-1.5 font-normal uppercase tracking-wider text-text-secondary">Why</th>
              <SortHeader label="Days" k="held" sort={sort} onSort={onSort} className="text-right" />
              <SortHeader label="Return" k="return" sort={sort} onSort={onSort} className="text-right" />
              <SortHeader label="P&L" k="pnl" sort={sort} onSort={onSort} className="text-right" />
              <th className="py-1.5 pl-3 font-normal uppercase tracking-wider text-text-secondary">Result</th>
            </tr>
          </thead>
          <tbody>
            {paged.rows.map((r) => {
              const ret = effectiveReturn(r);
              const isOpen = r.status === "OPEN";
              return (
                <Fragment key={r.id}>
                  <tr
                    onClick={() => setOpen(open === r.id ? null : r.id)}
                    className="cursor-pointer border-b border-[var(--hairline)] hover:bg-[var(--hover-wash)]"
                  >
                    <td className="py-1.5">
                      <span className="flex items-center gap-1.5">
                        <CompanyIcon ticker={r.ticker} logoUrl={logos[r.ticker]} title={r.ticker} size={16} />
                        <span className="font-semibold text-text-primary">{r.ticker}</span>
                        <span className={cn("px-1 text-[9px]", r.direction === "BULLISH" ? "bg-gains/15 text-gains" : "bg-losses/15 text-losses")}>
                          {r.direction === "BULLISH" ? "LONG" : "SHORT"}
                        </span>
                        {r.scaleIn && <span className="bg-accent/15 px-1 text-[9px] text-accent">ADD-ON</span>}
                        {r.takeProfitHalf && <span className="bg-gains/15 px-1 text-[9px] text-gains">½ PROFIT</span>}
                      </span>
                    </td>
                    <td className="py-1.5 text-text-secondary">{day(r.openedAt)}</td>
                    <td className="py-1.5 text-right text-text-primary">{px(r.entryPrice)}</td>
                    <td className="py-1.5 text-right text-text-secondary">{r.shares.toFixed(4)}</td>
                    <td className="py-1.5 text-right text-text-secondary">
                      <Sensitive className="text-[11px]">${r.amount.toFixed(0)}</Sensitive>
                    </td>
                    <td className="py-1.5 text-text-secondary">{isOpen ? <span className="text-accent">open</span> : day(r.closedAt)}</td>
                    <td className={cn("py-1.5 text-right", isOpen ? "italic text-text-tertiary" : "text-text-primary")}>
                      {isOpen ? px(r.currentPrice) : px(r.exitPrice)}
                    </td>
                    <td className="py-1.5 text-text-secondary">{isOpen ? "—" : (REASON[r.exitReason ?? ""] ?? r.exitReason)}</td>
                    <td className="py-1.5 text-right text-text-secondary">{r.heldDays ?? "—"}</td>
                    <td
                      className={cn("py-1.5 text-right", isOpen && "italic")}
                      style={{ color: ret == null ? undefined : ret >= 0 ? "var(--color-gains)" : "var(--color-losses)" }}
                    >
                      {ret == null ? "—" : `${signed(ret)}%`}
                    </td>
                    <td className="py-1.5 text-right" style={{ color: r.pnl == null ? undefined : r.pnl >= 0 ? "var(--color-gains)" : "var(--color-losses)" }}>
                      {r.pnl == null ? "—" : <Sensitive className="text-[11px]">${signed(r.pnl)}</Sensitive>}
                    </td>
                    <td className="py-1.5 pl-3">
                      {isOpen ? (
                        <span className="text-[10px] text-accent">OPEN</span>
                      ) : (
                        <span className={cn("px-1.5 py-0.5 text-[10px]", r.won ? "bg-gains/15 text-gains" : "bg-losses/15 text-losses")}>
                          {r.won ? "WON" : "LOST"}
                        </span>
                      )}
                    </td>
                  </tr>
                  {open === r.id && (
                    <tr className="border-b border-[var(--hairline)] bg-[var(--hover-wash)]">
                      <td colSpan={12} className="px-2 py-2 text-[11px] text-text-secondary">
                        Bought {new Date(r.openedAt).toLocaleString()} · stop {px(r.stopPrice)}
                        {r.targetPrice != null && ` · target ${px(r.targetPrice)}`}
                        {r.closedAt && ` · sold ${new Date(r.closedAt).toLocaleString()}`}
                        {r.vsSpyPct != null && ` · ${signed(r.vsSpyPct)}% vs the S&P 500`}
                        {isOpen && r.unrealizedPct != null && ` · live ${signed(r.unrealizedPct)}% (not yet realized)`}
                        {r.patternAdvice && <p className="mt-1">Pattern check at entry: {r.patternAdvice}</p>}
                        {r.styleFit && <p className="mt-1">Style fit at entry: {r.styleFit}</p>}
                        {r.closedAt ? (
                          <LessonForTrade
                            tradeId={r.id}
                            fallback={r.review && <p className="mt-1 italic">Post-mortem: {r.review}</p>}
                          />
                        ) : (
                          r.review && <p className="mt-1 italic">Post-mortem: {r.review}</p>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {paged.rows.length === 0 && (
              <tr>
                <td colSpan={12} className="py-4 text-center text-text-secondary">
                  No trades match these filters.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <Pager p={paged} />
    </MotionCard>
  );
}
