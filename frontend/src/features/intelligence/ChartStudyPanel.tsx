"use client";

import { getChartDetail, getChartStudies, type ChartDetail, type ChartStudyRow } from "@/lib/apiClient";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Skeleton } from "@/components/ui/Skeleton";
import { CandlestickChart } from "./CandlestickChart";
import { useEffect, useState } from "react";

const BIAS_CLS: Record<string, string> = {
  BULLISH: "bg-gains/15 text-gains",
  BEARISH: "bg-losses/15 text-losses",
  NEUTRAL: "bg-border/60 text-text-secondary",
};

const TREND_LABEL: Record<string, string> = { UPTREND: "▲ Uptrend", DOWNTREND: "▼ Downtrend", SIDEWAYS: "▬ Sideways" };

function pct(v: number | null): string {
  return v == null ? "–" : `${v >= 0 ? "+" : ""}${v.toFixed(1)}%`;
}

/**
 * Agent 10's chart studies: every tracked stock's trend, candlestick patterns, volume, support/resistance and
 * strength versus the S&P — ranked by how decisive the chart is. Selecting a row draws the actual candlestick
 * chart with the moving averages and levels the study was computed from.
 */
export function ChartStudyPanel() {
  const [rows, setRows] = useState<ChartStudyRow[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChartDetail | null>(null);

  useEffect(() => {
    let active = true;
    getChartStudies()
      .then((r) => {
        if (!active) return;
        setRows(r);
        if (r.length > 0) setSelected((s) => s ?? r[0].ticker);
      })
      .catch(() => active && setRows([]));
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!selected) return;
    let active = true;
    getChartDetail(selected)
      .then((d) => active && setDetail(d))
      .catch(() => active && setDetail(null));
    return () => {
      active = false;
    };
  }, [selected]);

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div>
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Chart study · Agent 10</h2>
        <p className="mt-1 text-xs text-text-secondary">
          Candlestick patterns, volume, the 20/50/200-day trend, support and resistance, and strength vs the S&amp;P 500 — read
          from each stock&apos;s daily candles. These feed the recommendations, the paper trades&apos; stop levels and Agent 11.
        </p>
      </div>

      {rows === null ? (
        <Skeleton className="h-32 w-full" />
      ) : rows.length === 0 ? (
        <p className="text-sm text-text-secondary">No chart studies yet — price history is still loading (needs about 30 daily candles per stock).</p>
      ) : (
        <>
          <div className="max-h-72 overflow-y-auto rounded-lg border border-border">
            <table className="w-full text-left text-xs">
              <thead className="sticky top-0 bg-surface text-[10px] uppercase tracking-wide text-text-secondary">
                <tr>
                  <th className="px-2 py-1.5">Stock</th>
                  <th className="px-2 py-1.5">Read</th>
                  <th className="px-2 py-1.5">Trend</th>
                  <th className="hidden px-2 py-1.5 sm:table-cell">20d</th>
                  <th className="hidden px-2 py-1.5 sm:table-cell">vs S&amp;P 60d</th>
                  <th className="px-2 py-1.5">Candlesticks</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr
                    key={r.ticker}
                    onClick={() => setSelected(r.ticker)}
                    className={`cursor-pointer border-t border-border transition-colors hover:bg-[var(--hover-wash)] ${selected === r.ticker ? "bg-accent/10" : ""}`}
                  >
                    <td className="px-2 py-1.5 font-semibold text-text-primary">
                      <Sensitive>{r.ticker}</Sensitive>
                    </td>
                    <td className="px-2 py-1.5">
                      <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${BIAS_CLS[r.bias]}`}>
                        {r.bias.toLowerCase()} {r.score >= 0 ? "+" : ""}
                        {r.score.toFixed(2)}
                      </span>
                    </td>
                    <td className="px-2 py-1.5 text-text-secondary">{TREND_LABEL[r.trend]}</td>
                    <td className="hidden px-2 py-1.5 tabular-nums text-text-secondary sm:table-cell">{pct(r.ret20d)}</td>
                    <td className="hidden px-2 py-1.5 tabular-nums text-text-secondary sm:table-cell">{r.relStrength60d == null ? "–" : `${r.relStrength60d >= 0 ? "+" : ""}${r.relStrength60d.toFixed(1)} pts`}</td>
                    <td className="px-2 py-1.5 text-text-secondary">{r.patterns.length === 0 ? "–" : r.patterns.join(", ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {detail && detail.study.ticker === selected && (
            <div className="flex flex-col gap-3 border-t border-border pt-3">
              <div className="flex flex-wrap items-baseline gap-2">
                <span className="text-sm font-bold text-text-primary">
                  <Sensitive>{detail.study.ticker}</Sensitive>
                </span>
                <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${BIAS_CLS[detail.study.bias]}`}>{detail.study.bias.toLowerCase()}</span>
                <span className="text-xs text-text-secondary">as of {detail.study.asOf}</span>
              </div>
              <CandlestickChart detail={detail} />
              <ul className="flex flex-col gap-1">
                {detail.notes.map((n, i) => (
                  <li key={i} className="text-xs text-text-secondary">
                    • {n}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </>
      )}
    </section>
  );
}
