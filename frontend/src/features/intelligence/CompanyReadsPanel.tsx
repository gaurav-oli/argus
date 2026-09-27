"use client";

import { getFilings, getFundamentals, type FilingRow, type FundamentalsRow } from "@/lib/apiClient";
import { Sensitive } from "@/features/privacy/Sensitive";
import { Skeleton } from "@/components/ui/Skeleton";
import { useEffect, useState } from "react";

const VALUATION_STYLE: Record<string, string> = {
  CHEAP: "bg-gains/15 text-gains",
  FAIR: "bg-[var(--hover-wash)] text-text-secondary",
  RICH: "bg-losses/15 text-losses",
};

function signed(n: number, digits = 2): string {
  return `${n >= 0 ? "+" : ""}${n.toFixed(digits)}`;
}

/**
 * The two "what the company itself says and what it costs" reads that back every verdict: Agent 12's fundamentals
 * (growth, margins, peers and a reverse DCF that asks what growth today's price assumes) and Agent 14's reading of the
 * newest earnings releases and 10-Q/10-K filings. Both feed the recommender, Agent 11, the paper Investor and Agent 9.
 */
export function FundamentalsPanel() {
  const [rows, setRows] = useState<FundamentalsRow[] | null>(null);
  useEffect(() => {
    getFundamentals().then(setRows).catch(() => setRows([]));
  }, []);

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div>
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Fundamentals & valuation · Agent 12</h2>
        <p className="mt-1 max-w-xl text-xs text-text-secondary">
          Growth, margins, balance sheet, earnings track record and peers — plus a reverse DCF: the growth today&apos;s price assumes versus what the
          company has actually delivered.
        </p>
      </div>
      {rows === null ? (
        <Skeleton className="h-20 w-full" />
      ) : rows.length === 0 ? (
        <p className="text-sm text-text-secondary">No fundamentals stored yet — they fill in daily from Finnhub.</p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {rows.slice(0, 12).map((r) => (
            <li key={r.ticker} className="rounded-lg border border-border px-3 py-2">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="w-14 shrink-0 text-sm font-bold text-text-primary">
                  <Sensitive>{r.ticker}</Sensitive>
                </span>
                <span
                  className={`text-xs font-semibold ${r.bias === "BULLISH" ? "text-gains" : r.bias === "BEARISH" ? "text-losses" : "text-text-secondary"}`}
                >
                  {r.bias.toLowerCase()} {signed(r.score)}
                </span>
                {r.valuationVerdict && (
                  <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${VALUATION_STYLE[r.valuationVerdict] ?? ""}`}>
                    {r.valuationVerdict === "RICH" ? "rich" : r.valuationVerdict === "CHEAP" ? "cheap" : "fairly priced"}
                  </span>
                )}
                {r.impliedGrowthPct != null && (
                  <span className="text-[11px] tabular-nums text-text-secondary">
                    price implies {r.impliedGrowthPct.toFixed(0)}%/yr growth
                    {r.deliveredGrowthPct != null && <> · delivered {r.deliveredGrowthPct.toFixed(0)}%</>}
                  </span>
                )}
                {r.peerPePremiumPct != null && (
                  <span className="text-[11px] tabular-nums text-text-secondary">P/E {signed(r.peerPePremiumPct, 0)}% vs peers</span>
                )}
              </div>
              {r.notes[0] && <p className="mt-1 text-[11px] text-text-secondary">{r.notes[0]}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const GUIDANCE_STYLE: Record<string, string> = {
  RAISED: "bg-gains/15 text-gains",
  LOWERED: "bg-losses/15 text-losses",
  MAINTAINED: "bg-[var(--hover-wash)] text-text-secondary",
};

const KIND_LABEL: Record<FilingRow["kind"], string> = {
  EARNINGS_RELEASE: "earnings release",
  QUARTERLY_REPORT: "quarterly report",
  ANNUAL_REPORT: "annual report",
};

export function FilingsPanel() {
  const [rows, setRows] = useState<FilingRow[] | null>(null);
  useEffect(() => {
    getFilings().then(setRows).catch(() => setRows([]));
  }, []);

  return (
    <section className="flex flex-col gap-3 rounded-xl border border-border bg-surface p-5">
      <div>
        <h2 className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">Filings & earnings reports · Agent 14</h2>
        <p className="mt-1 max-w-xl text-xs text-text-secondary">
          What companies themselves just said in SEC filings — guidance raised or lowered, tone, going-concern and new-risk language. Every figure is
          checked against the filing text before it is trusted.
        </p>
      </div>
      {rows === null ? (
        <Skeleton className="h-20 w-full" />
      ) : rows.length === 0 ? (
        <p className="text-sm text-text-secondary">No filings read yet — the reader checks SEC EDGAR every six hours.</p>
      ) : (
        <ul className="flex flex-col gap-1.5">
          {rows.slice(0, 12).map((r) => (
            <li key={`${r.ticker}-${r.filedAt}-${r.form}`} className="rounded-lg border border-border px-3 py-2">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="w-14 shrink-0 text-sm font-bold text-text-primary">
                  <Sensitive>{r.ticker}</Sensitive>
                </span>
                <span className="text-[11px] text-text-secondary">
                  {r.form} · {KIND_LABEL[r.kind]} · {r.filedAt}
                </span>
                {r.guidance && r.guidance !== "NONE" && (
                  <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${GUIDANCE_STYLE[r.guidance] ?? ""}`}>
                    guidance {r.guidance.toLowerCase()}
                  </span>
                )}
                {r.tone && <span className="text-[11px] text-text-secondary">tone {r.tone.toLowerCase()}</span>}
                <span className={`text-[11px] tabular-nums ${r.score > 0.1 ? "text-gains" : r.score < -0.1 ? "text-losses" : "text-text-secondary"}`}>
                  {signed(r.score)}
                </span>
              </div>
              <p className="mt-1 text-[11px] leading-relaxed text-text-secondary">{r.summary}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
