"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getFreshness,
  getHardware,
  type FreshnessView,
  type HardwareMetrics,
  type SourceFreshness,
} from "@/lib/apiClient";

/**
 * System health for the Operations view (Epic 9): host hardware (Story 9.5) and data-source freshness
 * (Story 9.7). Values the JVM can't measure on the host (Neural Engine, days-to-full) show as n/a.
 */
export function OpsHealth() {
  const [hw, setHw] = useState<HardwareMetrics | null>(null);
  const [fresh, setFresh] = useState<FreshnessView | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    Promise.allSettled([getHardware(), getFreshness()]).then((r) => {
      if (!active) return;
      if (r[0].status === "fulfilled") setHw(r[0].value);
      if (r[1].status === "fulfilled") setFresh(r[1].value);
      setLoaded(true);
    });
    return () => {
      active = false;
    };
  }, []);

  if (!loaded) {
    return (
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Skeleton className="h-56" />
        <Skeleton className="h-56" />
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {hw && <HardwareCard h={hw} />}
      {fresh && <FreshnessCard f={fresh} />}
    </div>
  );
}

function HardwareCard({ h }: { h: HardwareMetrics }) {
  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">Host hardware</h3>
        <p className="mt-0.5 text-xs text-text-secondary">Live RAM, SSD and CPU on the host.</p>
      </div>

      <Meter
        label="RAM"
        usedLabel={`${gb(h.ramUsedMb)} / ${gb(h.ramTotalMb)} GB`}
        pct={ratio(h.ramUsedMb, h.ramTotalMb)}
      />
      <Meter
        label="SSD"
        usedLabel={`${h.ssdUsedGb} / ${h.ssdTotalGb} GB`}
        pct={ratio(h.ssdUsedGb, h.ssdTotalGb)}
      />

      <div className="grid grid-cols-3 gap-3 border-t border-[var(--hairline)] pt-3 text-xs">
        <Stat label="CPU" value={h.cpuLoadPct === null ? "n/a" : `${h.cpuLoadPct}%`} />
        <Stat label="JVM heap" value={`${gb(h.jvmHeapUsedMb)} / ${gb(h.jvmHeapMaxMb)} GB`} />
        <Stat label="Neural Engine" value={h.neuralEngineLoadPct === null ? "n/a" : `${h.neuralEngineLoadPct}%`} />
      </div>
    </MotionCard>
  );
}

function FreshnessCard({ f }: { f: FreshnessView }) {
  return (
    <MotionCard index={1} interactive={false} className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">Data freshness</h3>
          <p className="mt-0.5 text-xs text-text-secondary">When each source last updated.</p>
        </div>
        {f.anyStale ? (
          <span className="rounded-full bg-warning/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-warning">
            stale sources
          </span>
        ) : (
          <span className="rounded-full bg-gains/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gains">
            all fresh
          </span>
        )}
      </div>

      <ul className="flex flex-col gap-2 text-xs">
        {f.sources.map((s) => (
          <li key={s.source} className="flex items-center justify-between gap-2">
            <span className="flex items-center gap-2">
              <span className={cnDot(s.stale)} />
              <span className="text-text-primary">{s.label}</span>
            </span>
            <span className={s.stale ? "font-mono text-warning" : "font-mono text-text-secondary"}>
              {age(s)}
            </span>
          </li>
        ))}
      </ul>
    </MotionCard>
  );
}

function Meter({ label, usedLabel, pct }: { label: string; usedLabel: string; pct: number }) {
  const color = pct >= 90 ? "var(--color-losses)" : pct >= 75 ? "var(--color-warning)" : "var(--color-accent)";
  return (
    <div>
      <div className="flex items-baseline justify-between text-xs">
        <span className="font-medium text-text-primary">{label}</span>
        <span className="font-mono text-text-secondary">{usedLabel}</span>
      </div>
      <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-[var(--hairline)]">
        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: color }} />
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[10px] uppercase tracking-wider text-text-secondary">{label}</p>
      <p className="mt-0.5 font-mono tabular-nums text-text-primary">{value}</p>
    </div>
  );
}

function cnDot(stale: boolean): string {
  return `h-1.5 w-1.5 rounded-full ${stale ? "bg-warning" : "bg-gains"}`;
}

function age(s: SourceFreshness): string {
  if (s.ageMinutes === null) return "never";
  const m = s.ageMinutes;
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function ratio(used: number, total: number): number {
  return total <= 0 ? 0 : Math.min(100, Math.round((used / total) * 100));
}

function gb(mb: number): number {
  return Math.round((mb / 1024) * 10) / 10;
}
