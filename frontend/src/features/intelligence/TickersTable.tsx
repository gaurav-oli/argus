"use client";

import { FilterChips, compareBy, useSort, type Sort } from "@/features/agents/tableKit";
import { TickerRow, type RosterRow } from "@/features/intelligence/TickerRoster";
import { cn } from "@/lib/utils";
import { useId, useMemo, useState } from "react";

type SortKey = "rank" | "ticker" | "action" | "conviction" | "deep" | "chart" | "valuation";
type ActionFilter = "all" | "buy" | "avoid" | "watch";
type ChartFilter = "all" | "BULLISH" | "BEARISH" | "NEUTRAL";

/** Strongest buy first, strongest avoid last; WATCH sits between as "no call". */
const ACTION_ORDER: Record<string, number> = { STRONG_BUY: 4, BUY: 3, WATCH: 2, AVOID: 1, STRONG_AVOID: 0 };
const VALUATION_ORDER: Record<string, number> = { CHEAP: 2, FAIR: 1, RICH: 0 };
/** Agent 11's verdict, best case first; an at-risk verdict ranks below all of them. */
const DEEP_ORDER: Record<string, number> = { WORTH_BUYING: 3, WAIT: 2, NOT_WORTH_BUYING: 1 };

const SORT_VALUE: Record<Exclude<SortKey, "rank">, (r: RosterRow) => number | string | null> = {
  ticker: (r) => r.ticker,
  action: (r) => ACTION_ORDER[r.action] ?? null,
  conviction: (r) => r.conviction,
  deep: (r) => (r.deepVerdict ? (r.deepVerdict.atRisk ? 0 : (DEEP_ORDER[r.deepVerdict.verdict] ?? null)) : null),
  chart: (r) => r.chart?.score ?? null,
  valuation: (r) => (r.valuation ? (VALUATION_ORDER[r.valuation] ?? null) : null),
};

const isBuy = (r: RosterRow) => r.action === "BUY" || r.action === "STRONG_BUY";
const isAvoid = (r: RosterRow) => r.action === "AVOID" || r.action === "STRONG_AVOID";

/**
 * The Tickers tab: every name Argus follows, with a ticker search that suggests matches from the
 * roster as you type (Enter on an exact match opens it), action/chart filter chips with counts, and
 * sortable columns. Until a column is clicked the roster keeps its own ranking — actionable calls
 * first, then conviction.
 */
export function TickersTable({
  rows,
  logos,
  onOpen,
}: {
  rows: RosterRow[];
  logos: Record<string, string | undefined>;
  onOpen: (ticker: string) => void;
}) {
  const listId = useId();
  const [query, setQuery] = useState("");
  const [action, setAction] = useState<ActionFilter>("all");
  const [chart, setChart] = useState<ChartFilter>("all");
  const [sort, onSort] = useSort<SortKey>({ key: "rank", dir: "desc" });

  const q = query.trim().toUpperCase();
  const visible = useMemo(() => {
    const filtered = rows.filter(
      (r) =>
        (!q || r.ticker.includes(q)) &&
        (action === "all" || (action === "buy" ? isBuy(r) : action === "avoid" ? isAvoid(r) : r.action === "WATCH")) &&
        (chart === "all" || r.chart?.bias === chart),
    );
    if (sort.key === "rank") return filtered;
    const get = SORT_VALUE[sort.key];
    // Ties fall back to the roster's own ranking, so equal values don't shuffle between renders.
    const rank = new Map(rows.map((r, i) => [r.ticker, i]));
    const cmp = compareBy(get, sort.dir);
    return [...filtered].sort((a, b) => cmp(a, b) || rank.get(a.ticker)! - rank.get(b.ticker)!);
  }, [rows, q, action, chart, sort]);

  const count = (pred: (r: RosterRow) => boolean) => rows.filter(pred).length;
  const exact = rows.find((r) => r.ticker === q);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <FilterChips
          label="Call"
          value={action}
          onChange={setAction}
          options={[
            { value: "all", label: "All", count: rows.length },
            { value: "buy", label: "Buy", count: count(isBuy) },
            { value: "avoid", label: "Avoid", count: count(isAvoid) },
            { value: "watch", label: "Watch", count: count((r) => r.action === "WATCH") },
          ]}
        />
        <FilterChips
          label="Chart"
          value={chart}
          onChange={setChart}
          options={[
            { value: "all", label: "Any chart" },
            { value: "BULLISH", label: "▲ Bullish", count: count((r) => r.chart?.bias === "BULLISH") },
            { value: "BEARISH", label: "▼ Bearish", count: count((r) => r.chart?.bias === "BEARISH") },
            { value: "NEUTRAL", label: "▬ Neutral", count: count((r) => r.chart?.bias === "NEUTRAL") },
          ]}
        />
        <label className="ml-auto flex items-center gap-1.5 font-mono text-[11px] text-text-secondary">
          <span aria-hidden className="text-accent">&gt;</span>
          <span className="sr-only">Search by ticker</span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && exact) onOpen(exact.ticker);
              if (e.key === "Escape") setQuery("");
            }}
            list={listId}
            placeholder="ticker"
            maxLength={12}
            autoComplete="off"
            spellCheck={false}
            className="w-28 border border-[var(--glass-border)] bg-transparent px-2 py-1 uppercase text-text-primary outline-none placeholder:normal-case focus:border-accent"
          />
          <datalist id={listId}>
            {rows.map((r) => (
              <option key={r.ticker} value={r.ticker}>
                {r.actionLabel}
              </option>
            ))}
          </datalist>
        </label>
      </div>

      <div className="rounded-xl border border-border bg-surface px-4">
        <div role="row" className="grid grid-cols-[1.5fr_0.9fr_0.6fr_0.9fr_0.9fr_0.9fr] gap-2 border-b border-[var(--hairline)] py-2 text-left font-mono text-[10px]">
          <SortButton label="Ticker" k="ticker" sort={sort} onSort={onSort} />
          <SortButton label="Call" k="action" sort={sort} onSort={onSort} />
          <SortButton label="Conv." k="conviction" sort={sort} onSort={onSort} />
          <SortButton label="Deep" k="deep" sort={sort} onSort={onSort} />
          <SortButton label="Chart" k="chart" sort={sort} onSort={onSort} />
          <SortButton label="Value" k="valuation" sort={sort} onSort={onSort} />
        </div>
        {visible.length === 0 ? (
          <p className="py-6 text-center text-sm text-text-secondary">No tickers match these filters.</p>
        ) : (
          visible.map((r, i) => <TickerRow key={r.ticker} row={r} logoUrl={logos[r.ticker]} index={i} onOpen={onOpen} showConviction />)
        )}
      </div>
      <p className="font-mono text-[10px] text-text-secondary">
        {visible.length} of {rows.length} tickers
        {sort.key !== "rank" && (
          <>
            {" · "}
            <button type="button" onClick={() => onSort("rank")} className="text-accent hover:underline">
              reset sort
            </button>
          </>
        )}
      </p>
    </div>
  );
}

/** A column header for the grid-based roster: same look as tableKit's SortHeader, minus the <th>. */
function SortButton({ label, k, sort, onSort }: { label: string; k: SortKey; sort: Sort<SortKey>; onSort: (k: SortKey) => void }) {
  const active = sort.key === k;
  return (
    <span role="columnheader" aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        onClick={() => onSort(k)}
        className={cn("uppercase tracking-wider hover:text-accent", active ? "text-accent" : "text-text-secondary")}
      >
        {label}
        <span aria-hidden className="ml-0.5 inline-block w-2">
          {active ? (sort.dir === "asc" ? "▲" : "▼") : ""}
        </span>
      </button>
    </span>
  );
}
