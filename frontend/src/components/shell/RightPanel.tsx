"use client";

import { useEffect, useState } from "react";
import {
  getLiveAlerts,
  getRecommendations,
  type LiveAlert,
  type RecommendationCard,
} from "@/lib/apiClient";
import { relTime } from "@/lib/time";

const tierColor = {
  critical: "var(--color-losses)",
  warning: "var(--color-warning)",
  info: "var(--color-accent)",
} as const;

/**
 * Right rail (PRD §12) — now wired to real data: the live alerts feed (/api/alerts/live) and the
 * latest recommendations. Frosted glass chrome so the ambient glow reads through. Desktop xl+ only.
 */
export function RightPanel() {
  const [alerts, setAlerts] = useState<LiveAlert[] | null>(null);
  const [recs, setRecs] = useState<RecommendationCard[] | null>(null);

  useEffect(() => {
    let active = true;
    getLiveAlerts().then((v) => active && setAlerts(v)).catch(() => active && setAlerts([]));
    getRecommendations().then((v) => active && setRecs(v)).catch(() => active && setRecs([]));
    return () => {
      active = false;
    };
  }, []);

  return (
    <aside className="glass-chrome hidden h-full w-80 shrink-0 flex-col overflow-y-auto border-l border-[var(--glass-border)] xl:flex">
      <Section title="Live Alerts">
        {alerts === null ? (
          <SkeletonRows n={3} />
        ) : alerts.length === 0 ? (
          <Empty label="All clear — no active alerts." />
        ) : (
          alerts.slice(0, 4).map((a) => (
            <div key={a.id} className="rounded-lg border border-[var(--hairline)] bg-[var(--hover-wash)] p-3">
              <div className="flex items-center gap-2">
                <span
                  className="h-1.5 w-1.5 shrink-0 rounded-full"
                  style={{ background: tierColor[a.tier], boxShadow: `0 0 8px ${tierColor[a.tier]}` }}
                />
                <p className="flex-1 truncate text-xs font-semibold text-text-primary">{a.title}</p>
                <span className="shrink-0 font-mono text-[10px] text-text-secondary">{relTime(a.time)}</span>
              </div>
              <p className="mt-1 line-clamp-2 text-[11px] leading-snug text-text-secondary">{a.body}</p>
            </div>
          ))
        )}
      </Section>

      <Section title="Recommendations">
        {recs === null ? (
          <SkeletonRows n={2} />
        ) : recs.length === 0 ? (
          <Empty label="No recommendations yet." />
        ) : (
          recs.slice(0, 3).map((r) => {
            const bull = r.direction === "BULLISH";
            const c = bull ? "var(--color-gains)" : "var(--color-losses)";
            return (
              <div key={r.id} className="rounded-lg border border-[var(--hairline)] bg-[var(--hover-wash)] p-3">
                <div className="flex items-center justify-between">
                  <span className="font-display text-sm font-bold text-text-primary">{r.ticker}</span>
                  <span
                    className="rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
                    style={{ color: c, background: `color-mix(in srgb, ${c} 14%, transparent)` }}
                  >
                    {bull ? "Bullish" : "Bearish"}
                  </span>
                </div>
                <div className="mt-2 flex items-center gap-3 font-mono text-[11px] text-text-secondary">
                  <span style={{ color: c }}>{Math.round(r.bullProbability * 100)}% bull</span>
                  <span>{Math.round(r.confidence * 100)}% conf</span>
                  {r.priceTarget != null && <span className="ml-auto">${r.priceTarget}</span>}
                </div>
              </div>
            );
          })
        )}
      </Section>
    </aside>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="border-b border-[var(--glass-border)] p-4 last:border-b-0">
      <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wide text-text-secondary">{title}</h2>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function SkeletonRows({ n }: { n: number }) {
  return (
    <>
      {Array.from({ length: n }).map((_, i) => (
        <div key={i} className="h-[58px] animate-pulse rounded-lg bg-border/40" />
      ))}
    </>
  );
}

function Empty({ label }: { label: string }) {
  return <p className="py-3 text-center text-[11px] text-text-secondary">{label}</p>;
}
