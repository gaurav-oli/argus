"use client";

import Link from "next/link";
import { useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getAccuracy,
  getCalibration,
  type AccuracyView,
  type CalibrationView,
  type WindowStat,
} from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";

/**
 * Compact paper-trust strip (S-B1): win rate, sample-size honesty, Brier, graduation, and
 * last-30d vs prior trend — lifted from Agents so Home / Intelligence can answer
 * “is the agent getting better?” without digging into Ops.
 */
export function TrustScoreboard({ className }: { className?: string }) {
  const [accuracy, setAccuracy] = useState<AccuracyView | null>(null);
  const [calibration, setCalibration] = useState<CalibrationView | null>(null);
  const [loaded, setLoaded] = useState(false);

  useAutoRefresh(
    () =>
      Promise.allSettled([getAccuracy(), getCalibration()]).then((r) => {
        if (r[0].status === "fulfilled") setAccuracy(r[0].value);
        if (r[1].status === "fulfilled") setCalibration(r[1].value);
        setLoaded(true);
      }),
    REFRESH.NORMAL,
  );

  if (!loaded) {
    return <Skeleton className={cn("h-[5.5rem] w-full", className)} />;
  }
  if (!accuracy) {
    return null;
  }

  const w = accuracy.last30d;
  const prior = accuracy.prior30d;
  const trend = trendLabel(w, prior);
  const brier = calibration?.brierScore ?? null;
  const meaningful = w.statisticallyMeaningful;

  return (
    <MotionCard
      index={0}
      interactive={false}
      className={cn("flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between", className)}
    >
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-text-secondary">
            Paper trust
          </p>
          {accuracy.graduationBadge && (
            <span className="rounded-full border border-accent/30 bg-accent/[0.08] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-accent">
              {accuracy.graduationBadge}
            </span>
          )}
          <span className="font-mono text-[10px] uppercase tracking-wide text-text-secondary">
            {accuracy.graduationState}
          </span>
        </div>

        <div className="mt-2 flex flex-wrap items-baseline gap-x-5 gap-y-2">
          <Metric
            label="Win rate · 30d"
            value={w.winRatePct === null ? "—" : `${w.winRatePct}%`}
            hint={`${w.wins}/${w.trades} closed`}
          />
          <Metric
            label="vs prior 30d"
            value={trend.text}
            hint={prior.trades === 0 ? "no prior sample" : `${prior.wins}/${prior.trades} prior`}
            tone={trend.tone}
          />
          <Metric
            label="Brier"
            value={brier == null ? "—" : brier.toFixed(3)}
            hint={
              calibration
                ? `${calibration.resolvedCount} resolved · 0 = perfect`
                : "calibration pending"
            }
          />
        </div>

        {!meaningful && (
          <p className="mt-2 text-[11px] italic leading-snug text-text-secondary">
            {w.trades === 0
              ? "No closed paper trades yet — results are not statistically meaningful."
              : `Only ${w.trades} closed trades in the last 30 days (need 20+) — results are not statistically meaningful.`}
          </p>
        )}
      </div>

      <Link
        href="/agents"
        className="shrink-0 font-mono text-[10px] uppercase tracking-[0.14em] text-accent underline-offset-4 hover:underline"
      >
        Full scoreboard →
      </Link>
    </MotionCard>
  );
}

function Metric({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string;
  hint: string;
  tone?: "up" | "down" | "flat";
}) {
  const color =
    tone === "up"
      ? "var(--color-gains)"
      : tone === "down"
        ? "var(--color-losses)"
        : "var(--color-text-primary)";
  return (
    <div>
      <p className="font-mono text-[9px] uppercase tracking-[0.14em] text-text-secondary">{label}</p>
      <p className="font-display text-2xl font-bold tabular-nums leading-none" style={{ color }}>
        {value}
      </p>
      <p className="mt-0.5 text-[10px] text-text-secondary">{hint}</p>
    </div>
  );
}

function trendLabel(
  last: WindowStat,
  prior: WindowStat,
): { text: string; tone: "up" | "down" | "flat" } {
  if (last.winRatePct == null || prior.winRatePct == null) {
    return { text: "—", tone: "flat" };
  }
  const delta = last.winRatePct - prior.winRatePct;
  if (delta > 0) return { text: `+${delta} pts`, tone: "up" };
  if (delta < 0) return { text: `${delta} pts`, tone: "down" };
  return { text: "flat", tone: "flat" };
}
