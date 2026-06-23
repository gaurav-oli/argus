"use client";

import { getPortfolioValue, type PortfolioSnapshot, type PositionValue } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useMemo, useState } from "react";

type SortKey =
  | "ticker" | "shares" | "costBasis" | "price" | "marketValue"
  | "dayPnl" | "dayPnlPercent" | "totalPnl" | "totalPnlPercent" | "weightPercent";

const COLUMNS: { key: SortKey; label: string; numeric: boolean; primary: boolean }[] = [
  { key: "ticker", label: "Ticker", numeric: false, primary: true },
  { key: "shares", label: "Shares", numeric: true, primary: false },
  { key: "costBasis", label: "Cost", numeric: true, primary: false },
  { key: "price", label: "Price", numeric: true, primary: false },
  { key: "marketValue", label: "Value", numeric: true, primary: true },
  { key: "dayPnl", label: "Day P&L", numeric: true, primary: false },
  { key: "dayPnlPercent", label: "Day %", numeric: true, primary: false },
  { key: "totalPnl", label: "Total P&L", numeric: true, primary: true },
  { key: "totalPnlPercent", label: "Total %", numeric: true, primary: false },
  { key: "weightPercent", label: "Weight", numeric: true, primary: false },
];

const num = (n: number | null, digits = 2) =>
  n == null ? "—" : n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits });
const pct = (n: number | null) => (n == null ? "—" : `${n.toFixed(2)}%`);
const pnlClass = (n: number | null) =>
  n == null || n === 0 ? "text-text-primary" : n > 0 ? "text-gains" : "text-losses";

/**
 * Sortable, colour-coded holdings table (Story 3.5, FR-3). Driven by the single live
 * `/topic/portfolio` snapshot (initial fetch + STOMP). Secondary columns collapse on mobile; tap a
 * row to expand its detail. Unpriced cells show "—".
 */
export function HoldingsTable() {
  const [positions, setPositions] = useState<PositionValue[]>([]);
  const [sortKey, setSortKey] = useState<SortKey>("weightPercent");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");
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

  const sorted = useMemo(() => {
    const rows = [...positions];
    const dir = sortDir === "asc" ? 1 : -1;
    rows.sort((a, b) => {
      const av = a[sortKey];
      const bv = b[sortKey];
      if (av == null && bv == null) return 0;
      if (av == null) return 1; // nulls always last
      if (bv == null) return -1;
      if (typeof av === "number" && typeof bv === "number") return (av - bv) * dir;
      return String(av).localeCompare(String(bv)) * dir;
    });
    return rows;
  }, [positions, sortKey, sortDir]);

  function toggleSort(key: SortKey) {
    if (key === sortKey) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir(key === "ticker" ? "asc" : "desc");
    }
  }

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
      <h3 className="text-sm font-medium text-text-primary">Holdings</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm tabular-nums">
          <thead>
            <tr className="text-xs uppercase tracking-wide text-text-secondary">
              {COLUMNS.map((c) => (
                <th
                  key={c.key}
                  onClick={() => toggleSort(c.key)}
                  className={`cursor-pointer select-none py-1 pr-4 font-medium hover:text-text-primary ${c.numeric ? "text-right" : "text-left"} ${c.primary ? "" : "hidden md:table-cell"}`}
                >
                  {c.label}
                  {sortKey === c.key && <span className="ml-1">{sortDir === "asc" ? "▲" : "▼"}</span>}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sorted.map((p) => (
              <tr
                key={p.ticker}
                onClick={() => setExpanded((t) => (t === p.ticker ? null : p.ticker))}
                className="cursor-pointer border-t border-border/60 md:cursor-default"
              >
                <td className="py-1.5 pr-4 font-medium text-text-primary">
                  {p.ticker}
                  {p.companyName && <span className="ml-2 hidden text-xs text-text-secondary lg:inline">{p.companyName}</span>}
                  {p.afterHours && <span className="ml-2 text-[10px] text-warning">AH</span>}
                  {expanded === p.ticker && (
                    <span className="mt-1 block text-xs font-normal text-text-secondary md:hidden">
                      {num(p.shares, 0)} sh · cost {num(p.costBasis)} {p.currency} · px {num(p.price)} ·{" "}
                      day <span className={pnlClass(p.dayPnl)}>{num(p.dayPnl)}</span> · wt {pct(p.weightPercent)}
                    </span>
                  )}
                </td>
                <td className="hidden py-1.5 pr-4 text-right text-text-primary md:table-cell">{num(p.shares, 0)}</td>
                <td className="hidden py-1.5 pr-4 text-right text-text-primary md:table-cell">{num(p.costBasis)}</td>
                <td className="hidden py-1.5 pr-4 text-right text-text-primary md:table-cell">{num(p.price)}</td>
                <td className="py-1.5 pr-4 text-right text-text-primary">{num(p.marketValue)}</td>
                <td className={`hidden py-1.5 pr-4 text-right md:table-cell ${pnlClass(p.dayPnl)}`}>{num(p.dayPnl)}</td>
                <td className={`hidden py-1.5 pr-4 text-right md:table-cell ${pnlClass(p.dayPnlPercent)}`}>{pct(p.dayPnlPercent)}</td>
                <td className={`py-1.5 pr-4 text-right ${pnlClass(p.totalPnl)}`}>{num(p.totalPnl)}</td>
                <td className={`hidden py-1.5 pr-4 text-right md:table-cell ${pnlClass(p.totalPnl)}`}>{pct(p.totalPnlPercent)}</td>
                <td className="hidden py-1.5 pr-4 text-right text-text-secondary md:table-cell">{pct(p.weightPercent)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
