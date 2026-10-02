"use client";

import { useCallback, useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";
import {
  getFreshness,
  getGraduation,
  resumeGraduation,
  type FreshnessView,
  type GraduationSummary,
} from "@/lib/apiClient";

const POLL_MS = 5 * 60_000; // this is "don't let a freeze go unnoticed for days", not a live ticker
const DISMISS_KEY = "argus.systemAlert.dismissedFingerprint";

/**
 * A system health problem Argus has already detected server-side — Agent 5 frozen, or a data source
 * gone stale — surfaced on every page, not just a quiet dot on the Agents page nobody happens to
 * visit. Built after a real incident: Agent 5 sat FROZEN for 4+ days with the freshness data already
 * showing it, because nothing put it in front of the user without them going looking for it. Renders
 * nothing when everything is healthy. Dismissible for the current browser session only — a fresh
 * session (tomorrow) sees it again if the problem is still real, so dismissing it once can't quietly
 * hide a freeze for days the way the silent push notification did.
 */
export function SystemAlertBanner() {
  const [graduation, setGraduation] = useState<GraduationSummary | null>(null);
  const [freshness, setFreshness] = useState<FreshnessView | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(() => {
    if (typeof window === "undefined") return null; // SSR — no storage to read yet
    try {
      return sessionStorage.getItem(DISMISS_KEY);
    } catch {
      return null; // private browsing / storage blocked — just never treat anything as dismissed
    }
  });
  const reduce = useReducedMotion();

  const load = useCallback(() => {
    getGraduation().then(setGraduation).catch(() => {});
    getFreshness().then(setFreshness).catch(() => {});
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, [load]);

  const frozen = graduation?.state === "FROZEN";
  const staleSources = freshness?.sources.filter((s) => s.stale) ?? [];
  const alert: { key: string; tone: "losses" | "warning"; text: string } | null = frozen
    ? { key: "frozen", tone: "losses", text: "🧊 Agent 5 has frozen — it stopped producing any new recommendation until this is reviewed." }
    : staleSources.length > 0
      ? {
          key: `stale:${staleSources.map((s) => s.source).join(",")}`,
          tone: "warning",
          text: `⚠ ${staleSources.length === 1 ? staleSources[0].label : `${staleSources.length} data sources`} ${staleSources.length === 1 ? "hasn't" : "haven't"} updated recently — something may be stuck.`,
        }
      : null;

  if (!alert || dismissed === alert.key) return null;

  function dismiss() {
    if (!alert) return;
    try {
      sessionStorage.setItem(DISMISS_KEY, alert.key);
    } catch {
      // best-effort only
    }
    setDismissed(alert.key);
  }

  return (
    <motion.div
      key={alert.key}
      initial={reduce ? false : { height: 0, opacity: 0 }}
      animate={{ height: "auto", opacity: 1 }}
      transition={{ duration: 0.25, ease: "easeOut" }}
      className="overflow-hidden"
    >
      <div
        className={`flex flex-wrap items-center justify-between gap-3 border-b px-4 py-2 text-xs sm:px-6 ${
          alert.tone === "losses" ? "border-losses/30 bg-losses/10 text-losses" : "border-warning/30 bg-warning/10 text-warning"
        }`}
      >
        <span className="font-medium">{alert.text}</span>
        <div className="flex items-center gap-2">
          {frozen && <ResumeButton onResumed={load} />}
          <button
            type="button"
            onClick={dismiss}
            className="rounded px-2 py-0.5 text-[11px] font-medium opacity-70 hover:opacity-100"
            aria-label="Dismiss for this session"
          >
            Dismiss
          </button>
        </div>
      </div>
    </motion.div>
  );
}

/** Two clicks to confirm — unfreezing is a real, state-changing decision, not something a stray tap should trigger. */
function ResumeButton({ onResumed }: { onResumed: () => void }) {
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!armed) return;
    const t = setTimeout(() => setArmed(false), 4000);
    return () => clearTimeout(t);
  }, [armed]);

  async function click() {
    if (!armed) {
      setArmed(true);
      return;
    }
    setBusy(true);
    try {
      await resumeGraduation();
      onResumed();
    } catch {
      // best-effort — the banner just stays up and they can try again
    } finally {
      setBusy(false);
      setArmed(false);
    }
  }

  return (
    <button
      type="button"
      onClick={() => void click()}
      disabled={busy}
      className="rounded border border-losses/40 bg-losses/10 px-2 py-0.5 text-[11px] font-semibold text-losses hover:bg-losses/20 disabled:opacity-50"
    >
      {busy ? "Resuming…" : armed ? "Click again to confirm" : "Resume Agent 5"}
    </button>
  );
}
