"use client";

import { useCallback, useEffect, useState } from "react";
import { motion, useReducedMotion, AnimatePresence } from "motion/react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getBackupStatus,
  getFreshness,
  getHardware,
  triggerBackup,
  type BackupKindStatus,
  type BackupStatusView,
  type BackupTriggerStatus,
  type BackupView,
  type FreshnessView,
  type HardwareMetrics,
  type SourceFreshness,
} from "@/lib/apiClient";

/**
 * System health for the Operations view (Epic 9): host hardware (Story 9.5), data-source freshness
 * (Story 9.7), and backup status (Story 10.2). Values the JVM can't measure show as n/a.
 */
/** How often to poll while a triggered backup is RUNNING (no real progress %, so this just needs to
 * catch SUCCESS/FAILED reasonably promptly). */
const POLL_MS = 1500;
/** How long the SUCCESS state lingers before the button reverts to idle. */
const SUCCESS_LINGER_MS = 2500;

export function OpsHealth() {
  const [hw, setHw] = useState<HardwareMetrics | null>(null);
  const [fresh, setFresh] = useState<FreshnessView | null>(null);
  const [backup, setBackup] = useState<BackupView | null>(null);
  const [loaded, setLoaded] = useState(false);

  const loadBackup = useCallback(() => {
    getBackupStatus().then(setBackup).catch(() => {});
  }, []);

  useEffect(() => {
    let active = true;
    Promise.allSettled([getHardware(), getFreshness(), getBackupStatus()]).then((r) => {
      if (!active) return;
      if (r[0].status === "fulfilled") setHw(r[0].value);
      if (r[1].status === "fulfilled") setFresh(r[1].value);
      if (r[2].status === "fulfilled") setBackup(r[2].value);
      setLoaded(true);
    });
    return () => {
      active = false;
    };
  }, []);

  // Poll while a backup is actively running; stop the instant it settles.
  useEffect(() => {
    if (backup?.trigger.state !== "RUNNING") return;
    const t = setInterval(loadBackup, POLL_MS);
    return () => clearInterval(t);
  }, [backup?.trigger.state, loadBackup]);

  // SUCCESS/FAILED linger briefly for the animation, then quietly revert to idle.
  useEffect(() => {
    if (backup?.trigger.state !== "SUCCESS" && backup?.trigger.state !== "FAILED") return;
    const t = setTimeout(() => {
      setBackup((b) => (b ? { ...b, trigger: { ...b.trigger, state: "IDLE", message: null } } : b));
    }, SUCCESS_LINGER_MS);
    return () => clearTimeout(t);
  }, [backup?.trigger.state]);

  const onBackUpNow = useCallback(async () => {
    const v = await triggerBackup().catch(() => null);
    if (v) setBackup(v);
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
      {backup?.status.enabled && (
        <BackupCard b={backup.status} trigger={backup.trigger} onBackUpNow={onBackUpNow} />
      )}
    </div>
  );
}

function HardwareCard({ h }: { h: HardwareMetrics }) {
  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div>
        <h3 className="font-display text-base font-semibold text-text-primary">Runtime resources</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          RAM, SSD &amp; CPU of the app&apos;s Docker runtime — not the full Mac. Native Ollama isn&apos;t counted.
        </p>
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

function BackupCard({
  b,
  trigger,
  onBackUpNow,
}: {
  b: BackupStatusView;
  trigger: BackupTriggerStatus;
  onBackUpNow: () => void;
}) {
  const healthy = b.destinationConnected && b.full != null && !b.full.stale;
  return (
    <MotionCard index={2} interactive={false} className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">Backups</h3>
          <p className="mt-0.5 text-xs text-text-secondary">
            Full dump every 6h (14-day retention) · critical tables every 15 min.
          </p>
        </div>
        {!b.destinationConnected ? (
          <span className="rounded-full bg-losses/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-losses">
            drive disconnected
          </span>
        ) : healthy ? (
          <span className="rounded-full bg-gains/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-gains">
            healthy
          </span>
        ) : (
          <span className="rounded-full bg-warning/10 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-warning">
            stale
          </span>
        )}
      </div>
      <ul className="flex flex-col gap-2 text-xs">
        <BackupRow label="Full backup" k={b.full} />
        <BackupRow label="Critical tables" k={b.critical} />
      </ul>
      <div className="border-t border-[var(--hairline)] pt-3">
        <BackupButton trigger={trigger} onBackUpNow={onBackUpNow} disabled={!b.destinationConnected} />
      </div>
    </MotionCard>
  );
}

/**
 * "Back Up Now" — an indeterminate-progress cloud-upload animation while RUNNING (an arrow pulsing
 * upward into the cloud, since a real percentage isn't available), a stroke-drawn checkmark + a
 * brief particle burst on SUCCESS, and a shake + inline error on FAILED. Reduced-motion users get the
 * same state changes with the decorative parts (looping pulse, burst, shake) skipped.
 */
function BackupButton({
  trigger,
  onBackUpNow,
  disabled,
}: {
  trigger: BackupTriggerStatus;
  onBackUpNow: () => void;
  disabled: boolean;
}) {
  const reduce = useReducedMotion();
  const running = trigger.state === "RUNNING";
  const succeeded = trigger.state === "SUCCESS";
  const failed = trigger.state === "FAILED";

  return (
    <div className="flex flex-col gap-1.5">
      <motion.button
        type="button"
        onClick={onBackUpNow}
        disabled={disabled || running}
        animate={!reduce && failed ? { x: [0, -6, 6, -4, 4, 0] } : { x: 0 }}
        transition={{ duration: 0.4 }}
        className={`relative flex items-center justify-center gap-2 overflow-hidden rounded-lg px-3 py-2 text-xs font-semibold transition-colors disabled:cursor-not-allowed ${
          succeeded
            ? "bg-gains/15 text-gains"
            : failed
              ? "bg-losses/15 text-losses"
              : "bg-accent/15 text-accent hover:bg-accent/25 disabled:opacity-50"
        }`}
      >
        <CloudUploadIcon state={trigger.state} reduce={!!reduce} />
        <span>
          {running ? "Backing up…" : succeeded ? "Backed up" : failed ? "Backup failed — retry" : "Back Up Now"}
        </span>

        {running && (
          <motion.span
            aria-hidden
            className="absolute inset-x-0 bottom-0 h-0.5 bg-accent"
            initial={{ x: "-100%" }}
            animate={reduce ? { x: "0%" } : { x: ["-100%", "100%"] }}
            transition={reduce ? { duration: 0 } : { duration: 1.1, repeat: Infinity, ease: "easeInOut" }}
          />
        )}
      </motion.button>

      <AnimatePresence>
        {failed && trigger.message && (
          <motion.p
            initial={reduce ? false : { opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            className="text-[11px] text-losses"
          >
            {trigger.message}
          </motion.p>
        )}
      </AnimatePresence>
    </div>
  );
}

function CloudUploadIcon({ state, reduce }: { state: BackupTriggerStatus["state"]; reduce: boolean }) {
  const size = 14;
  if (state === "SUCCESS") {
    return (
      <span className="relative inline-flex h-3.5 w-3.5 items-center justify-center">
        {!reduce && <BurstParticles />}
        <motion.svg
          width={size}
          height={size}
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={3}
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden
        >
          <motion.path
            d="M20 6 9 17l-5-5"
            initial={reduce ? false : { pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: 0.35, ease: "easeOut" }}
          />
        </motion.svg>
      </span>
    );
  }
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" aria-hidden>
      <path
        d="M7 18a4.5 4.5 0 0 1-.5-8.97A5.5 5.5 0 0 1 17.3 8.02 4 4 0 0 1 17 16H7Z"
        stroke="currentColor"
        strokeWidth={1.8}
        strokeLinejoin="round"
      />
      <motion.g
        animate={state === "RUNNING" && !reduce ? { y: [3, -3, 3], opacity: [0.5, 1, 0.5] } : { y: 1, opacity: 1 }}
        transition={state === "RUNNING" && !reduce ? { duration: 1.1, repeat: Infinity, ease: "easeInOut" } : undefined}
      >
        <path d="M12 15V9M9 12l3-3 3 3" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" />
      </motion.g>
    </svg>
  );
}

/** A handful of small dots bursting outward and fading — the "cool" success flourish. Purely
 * decorative, so it's the one piece skipped outright for reduced-motion rather than just simplified. */
function BurstParticles() {
  const dots = [0, 60, 120, 180, 240, 300];
  return (
    <span className="pointer-events-none absolute inset-0">
      {dots.map((deg) => (
        <motion.span
          key={deg}
          className="absolute left-1/2 top-1/2 h-1 w-1 rounded-full bg-gains"
          initial={{ x: 0, y: 0, opacity: 1, scale: 1 }}
          animate={{
            x: Math.cos((deg * Math.PI) / 180) * 14,
            y: Math.sin((deg * Math.PI) / 180) * 14,
            opacity: 0,
            scale: 0,
          }}
          transition={{ duration: 0.5, ease: "easeOut" }}
        />
      ))}
    </span>
  );
}

function BackupRow({ label, k }: { label: string; k: BackupKindStatus | null }) {
  const stale = k == null || k.stale;
  return (
    <li className="flex items-center justify-between gap-2">
      <span className="flex items-center gap-2">
        <span className={cnDot(stale)} />
        <span className="text-text-primary">{label}</span>
      </span>
      <span className={stale ? "font-mono text-warning" : "font-mono text-text-secondary"}>
        {k?.lastSuccessAt == null
          ? "never"
          : `${sinceMinutes(k.lastSuccessAt)} · ${sizeKb(k.lastSizeBytes)}`}
      </span>
    </li>
  );
}

function sinceMinutes(iso: string): string {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (mins < 60) return `${mins}m ago`;
  const h = Math.floor(mins / 60);
  return h < 48 ? `${h}h ago` : `${Math.floor(h / 24)}d ago`;
}

function sizeKb(bytes: number | null): string {
  if (bytes == null) return "—";
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
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
