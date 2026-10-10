"use client";

import { useMemo, useState } from "react";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getStyleFit, type StyleFitCell, type StyleFitReport } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { FilterChips } from "./tableKit";

/**
 * S-B6 — which playbooks win on which kinds of names. Rows are the evidence family a call led with; columns
 * are the buckets of one style dimension at entry (volatility, sector, price band, regime, trend). Cells under
 * the sample-size guard are dimmed and never tilt a trade; the rest tilt the paper Investor's size by ±25%
 * when a playbook runs clearly hotter or colder on that kind of name than it does overall.
 */

const FAMILY_LABEL: Record<string, string> = {
  NEWS: "News",
  SOCIAL: "Social",
  INTERNET: "Internet",
  INSIDER: "Insider",
  TECHNICAL: "Chart",
  FUNDAMENTAL: "Fundamentals",
  FILINGS: "Filings",
  ACADEMIC: "Academic",
  DEEP: "Deep Analyst",
  MACRO: "Macro",
  CALENDAR: "Calendar",
};

function tone(c: StyleFitCell | undefined, overall: number | null): string {
  if (!c || c.winRatePct == null) return "text-text-tertiary";
  if (!c.enough) return "text-text-tertiary opacity-60";
  if (overall == null) return "text-text-primary";
  const gap = c.winRatePct - overall;
  return gap >= 10 ? "bg-gains/15 text-gains" : gap <= -10 ? "bg-losses/12 text-losses" : "text-text-primary";
}

export function StyleFitMatrix() {
  const [report, setReport] = useState<StyleFitReport | null | undefined>(undefined);
  const [dim, setDim] = useState("vol");

  useAutoRefresh(
    () =>
      getStyleFit()
        .then(setReport)
        .catch(() => setReport((prev) => (prev === undefined ? null : prev))),
    REFRESH.NORMAL,
  );

  const view = useMemo(() => {
    if (!report) return null;
    const cells = report.cells.filter((c) => c.dimension === dim);
    const buckets = [...new Set(cells.map((c) => c.bucket))].sort();
    const families = report.families.filter((f) => cells.some((c) => c.family === f.family));
    const at = (family: string, bucket: string) => cells.find((c) => c.family === family && c.bucket === bucket);
    return { buckets, families, at };
  }, [report, dim]);

  return (
    <MotionCard index={4} interactive={false} className="flex flex-col gap-3" id="style-fit">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">Which playbooks win where</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          Closed paper trades by the evidence a call led with × the kind of name it was. Green/red cells beat/lag that
          playbook&apos;s own overall win rate by 10+ points and tilt the next trade&apos;s size ±25%. Dimmed cells have fewer
          than {report?.minSample ?? 10} trades and change nothing.
        </p>
      </div>
      {report === undefined ? (
        <Skeleton className="h-40" />
      ) : report === null ? (
        <p className="py-6 text-center text-xs text-text-secondary">Couldn&apos;t load the style-fit report.</p>
      ) : report.families.length === 0 ? (
        <p className="py-6 text-center text-xs text-text-secondary">
          No closed paper trades with a recorded playbook yet — this fills in as trades close.
        </p>
      ) : (
        <>
          <FilterChips
            label="Style"
            value={dim}
            onChange={setDim}
            options={Object.entries(report.dimensions).map(([value, label]) => ({ value, label }))}
          />
          {view && view.buckets.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse font-mono text-[11px]">
                <thead>
                  <tr className="border-b border-[var(--hairline)]">
                    <th className="py-1.5 pr-3 text-left font-normal uppercase tracking-wider text-text-secondary">Playbook</th>
                    <th className="px-2 py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">Overall</th>
                    {view.buckets.map((b) => (
                      <th key={b} className="px-2 py-1.5 text-right font-normal uppercase tracking-wider text-text-secondary">
                        {b.replace(/_/g, " ").toLowerCase()}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {view.families.map((f) => (
                    <tr key={f.family} className="border-b border-[var(--hairline)] last:border-b-0">
                      <td className="py-1.5 pr-3 font-sans text-xs text-text-primary">{FAMILY_LABEL[f.family] ?? f.family}</td>
                      <td className="px-2 py-1.5 text-right text-text-secondary">
                        {f.winRatePct ?? "—"}% <span className="text-text-tertiary">({f.trades})</span>
                      </td>
                      {view.buckets.map((b) => {
                        const c = view.at(f.family, b);
                        return (
                          <td
                            key={b}
                            className={`px-2 py-1.5 text-right ${tone(c, f.winRatePct)}`}
                            title={c ? `${c.wins} of ${c.trades} won${c.avgReturnPct != null ? ` · avg ${c.avgReturnPct}%` : ""}${c.enough ? "" : " · under the sample guard"}` : "no trades"}
                          >
                            {c ? (
                              <>
                                {c.winRatePct}% <span className="text-text-tertiary">({c.trades})</span>
                              </>
                            ) : (
                              "·"
                            )}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="py-4 text-center text-xs text-text-secondary">No trades recorded this style yet.</p>
          )}
        </>
      )}
    </MotionCard>
  );
}
