"use client";

import { getHealthScoreHistory, type HealthPoint, type HealthScoreResult } from "@/lib/apiClient";
import { useEffect, useState } from "react";
import { createPortal } from "react-dom";

/** Tiny inline sparkline (no charting lib for a ~30-point line). Scores are 0–100. */
function Sparkline({ points }: { points: HealthPoint[] }) {
  if (points.length < 2) {
    return <p className="text-xs text-text-secondary">Trend builds up daily.</p>;
  }
  const w = 200;
  const h = 40;
  const step = w / (points.length - 1);
  const path = points
    .map((p, i) => `${i === 0 ? "M" : "L"}${(i * step).toFixed(1)},${(h - (p.score / 100) * h).toFixed(1)}`)
    .join(" ");
  return (
    <svg width={w} height={h} viewBox={`0 0 ${w} ${h}`} className="w-full" preserveAspectRatio="none">
      <path d={path} fill="none" stroke="var(--color-accent)" strokeWidth="2" strokeLinejoin="round" />
    </svg>
  );
}

function direction(points: HealthPoint[]): { label: string; cls: string } {
  if (points.length < 2) return { label: "—", cls: "text-text-secondary" };
  const diff = points[points.length - 1].score - points[0].score;
  if (diff >= 2) return { label: "▲ improving", cls: "text-gains" };
  if (diff <= -2) return { label: "▼ declining", cls: "text-losses" };
  return { label: "▬ stable", cls: "text-text-secondary" };
}

/**
 * Health Score breakdown (Story 3.9, FR-7) — a dismissible popover listing every point deduction
 * with its reason + suggested fix, plus a 30-day trend. Composes the existing score (passed in)
 * with the trend endpoint; no recompute. Dismiss via click-away or Escape.
 */
export function HealthScoreBreakdown({ result, onClose }: { result: HealthScoreResult; onClose: () => void }) {
  const [history, setHistory] = useState<HealthPoint[]>([]);

  useEffect(() => {
    let active = true;
    getHealthScoreHistory(30)
      .then((h) => active && setHistory(h))
      .catch(() => {});
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => {
      active = false;
      window.removeEventListener("keydown", onKey);
    };
  }, [onClose]);

  const trend = direction(history);

  return (
    <>
      {/* Portalled: this badge lives in TopBar's glass-chrome header, whose backdrop-filter makes
          it the containing block for `position: fixed` descendants — without the portal, this
          click-away catcher would only cover the 64px header strip instead of the full viewport,
          so clicking anywhere else on the page wouldn't dismiss the popover. */}
      {createPortal(<div className="fixed inset-0 z-40" onClick={onClose} aria-hidden />, document.body)}
      <div
        role="dialog"
        aria-label="Health score breakdown"
        className="absolute right-0 top-full z-50 mt-2 w-80 rounded-xl border border-border bg-surface/95 p-4 shadow-xl backdrop-blur"
      >
        <div className="mb-3 flex items-baseline justify-between">
          <h3 className="text-sm font-medium text-text-primary">Health Score · {result.score}/100</h3>
          <span className={`text-xs font-medium ${trend.cls}`}>{trend.label}</span>
        </div>

        <Sparkline points={history} />

        <div className="mt-3 flex flex-col gap-2">
          {result.deductions.length === 0 ? (
            <p className="text-sm text-gains">Nothing is dragging your score down — nicely balanced.</p>
          ) : (
            result.deductions.map((d) => (
              <div key={d.code} className="flex flex-col gap-0.5 border-l-2 border-losses/60 pl-2">
                <div className="flex items-baseline justify-between">
                  <span className="text-sm text-text-primary">{d.reason}</span>
                  <span className="ml-2 text-sm font-medium text-losses tabular-nums">−{d.points}</span>
                </div>
                <span className="text-xs text-text-secondary">{d.suggestion}</span>
              </div>
            ))
          )}
        </div>

        <p className="mt-3 border-t border-border/60 pt-2 text-[11px] leading-relaxed text-text-secondary/80">
          Rule-based score (concentration, diversification &amp; data quality). Agent-sentiment and
          open-risk inputs are not yet included.
        </p>
      </div>
    </>
  );
}
