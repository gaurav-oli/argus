"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getAccuracy,
  getAttribution,
  getCalibration,
  type AccuracyView,
  type AttributionView,
  type CalibrationView,
  type WindowStat,
} from "@/lib/apiClient";
import { cn } from "@/lib/utils";

/**
 * Agent 5 performance (Epic 9, Stories 9.2–9.4): accuracy over time windows, per-agent contribution
 * attribution, and probability calibration. Read-only; every figure comes from recorded outcomes,
 * and small samples are labelled honestly ("not yet meaningful" / "insufficient data").
 */
export function AgentPerformance() {
  const [accuracy, setAccuracy] = useState<AccuracyView | null>(null);
  const [attribution, setAttribution] = useState<AttributionView | null>(null);
  const [calibration, setCalibration] = useState<CalibrationView | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    Promise.allSettled([getAccuracy(), getAttribution(), getCalibration()]).then((r) => {
      if (!active) return;
      if (r[0].status === "fulfilled") setAccuracy(r[0].value);
      if (r[1].status === "fulfilled") setAttribution(r[1].value);
      if (r[2].status === "fulfilled") setCalibration(r[2].value);
      setLoaded(true);
    });
    return () => {
      active = false;
    };
  }, []);

  if (!loaded) {
    return (
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Skeleton className="h-52 lg:col-span-2" />
        <Skeleton className="h-60" />
        <Skeleton className="h-60" />
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {accuracy && <AccuracyCard a={accuracy} />}
      {attribution && <AttributionCard a={attribution} />}
      {calibration && <CalibrationCard c={calibration} />}
    </div>
  );
}

function AccuracyCard({ a }: { a: AccuracyView }) {
  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4 lg:col-span-2">
      <SectionHead title="Agent 5 accuracy" sub="How often resolved recommendations were right.">
        {a.graduationBadge && (
          <span className="rounded-full border border-accent/30 bg-accent/[0.08] px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-accent">
            {a.graduationBadge}
          </span>
        )}
      </SectionHead>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <WindowTile label="All time" w={a.all} />
        <WindowTile label="Last 30 days" w={a.last30d} />
        <WindowTile label="Last 10" w={a.last10} />
      </div>

      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 border-t border-[var(--hairline)] pt-3 text-xs text-text-secondary">
        <span>
          Issued <span className="font-mono text-text-primary">{a.totalIssued}</span>
        </span>
        <span>
          Taken <span className="font-mono text-gains">{a.taken}</span>
        </span>
        <span>
          Declined <span className="font-mono text-text-primary">{a.declined}</span>
        </span>
      </div>
    </MotionCard>
  );
}

function WindowTile({ label, w }: { label: string; w: WindowStat }) {
  return (
    <div className="rounded-xl border border-[var(--hairline)] bg-[var(--hover-wash)] px-4 py-3">
      <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">{label}</p>
      <p className="mt-1 font-display text-2xl font-bold tabular-nums text-text-primary">
        {w.winRatePct === null ? "—" : `${w.winRatePct}%`}
      </p>
      <p className="mt-0.5 text-xs text-text-secondary">
        {w.wins}/{w.trades} won
      </p>
      {!w.statisticallyMeaningful && (
        <p className="mt-1 text-[10px] italic text-text-secondary/80">not yet statistically meaningful</p>
      )}
    </div>
  );
}

function AttributionCard({ a }: { a: AttributionView }) {
  const max = Math.max(1, ...a.agents.map((x) => x.contributionPct));
  return (
    <MotionCard index={1} interactive={false} className="flex flex-col gap-4">
      <SectionHead title="Contribution" sub="Each agent's share of the signal weight." />
      {a.agents.length === 0 ? (
        <Empty>No signals recorded yet.</Empty>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {a.agents.map((x) => (
            <li key={x.agent}>
              <div className="flex items-baseline justify-between gap-2 text-xs">
                <span className="truncate font-mono text-text-primary">
                  {x.agent}
                  {x.underperformer && (
                    <span className="ml-1.5 rounded px-1 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-warning">
                      low
                    </span>
                  )}
                </span>
                <span className="shrink-0 font-mono tabular-nums text-text-secondary">
                  {x.contributionPct.toFixed(1)}%
                </span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-[var(--hairline)]">
                <div
                  className="h-full rounded-full"
                  style={{
                    width: `${(x.contributionPct / max) * 100}%`,
                    backgroundColor: x.underperformer ? "var(--color-warning)" : "var(--color-accent)",
                  }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </MotionCard>
  );
}

function CalibrationCard({ c }: { c: CalibrationView }) {
  // Only bins that have any samples are worth showing as rows.
  const rows = c.bins.filter((b) => b.count > 0);
  return (
    <MotionCard index={2} interactive={false} className="flex flex-col gap-4">
      <SectionHead title="Calibration" sub="Stated probability vs actual hit rate." />
      {c.resolvedCount === 0 ? (
        <Empty>No resolved recommendations yet — calibration needs outcomes.</Empty>
      ) : (
        <ul className="flex flex-col gap-2 text-xs">
          {rows.map((b) => (
            <li key={b.lowPct} className="flex items-center gap-3">
              <span className="w-16 shrink-0 font-mono text-text-secondary">
                {b.lowPct}–{b.highPct}%
              </span>
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[var(--hairline)]">
                <div
                  className="h-full rounded-full"
                  style={{
                    width: `${b.actualHitRatePct ?? 0}%`,
                    backgroundColor: b.sufficient ? "var(--color-gains)" : "var(--color-text-secondary)",
                  }}
                />
              </div>
              <span className="w-10 shrink-0 text-right font-mono tabular-nums text-text-primary">
                {b.actualHitRatePct === null ? "—" : `${b.actualHitRatePct}%`}
              </span>
              <span className="w-20 shrink-0 text-right text-[10px] text-text-secondary">
                {b.sufficient ? `n=${b.count}` : "insufficient"}
              </span>
            </li>
          ))}
        </ul>
      )}
      <p className="text-[10px] text-text-secondary/80">
        Bins below {c.minSampleSize} samples are marked insufficient · {c.resolvedCount} resolved.
      </p>
    </MotionCard>
  );
}

function SectionHead({
  title,
  sub,
  children,
}: {
  title: string;
  sub?: string;
  children?: React.ReactNode;
}) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">{title}</h3>
        {sub && <p className="mt-0.5 text-xs text-text-secondary">{sub}</p>}
      </div>
      {children}
    </div>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className={cn("py-6 text-center text-xs text-text-secondary")}>{children}</p>;
}
