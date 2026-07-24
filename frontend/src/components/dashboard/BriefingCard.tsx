"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import {
  getLatestBriefing,
  getMarketPulse,
  refreshMarketPulse,
  type Briefing,
  type MarketPulse,
} from "@/lib/apiClient";
import { absTime } from "@/lib/time";

/**
 * Morning Briefing (Epic 8, FR-16) — the pinned card at the top of the dashboard. Shows the latest
 * local-model narrative over the portfolio, overnight news, recommendations, and today's calendar,
 * plus an on-demand "market pulse" that summarizes the market-impacting news captured so far.
 *
 * The pulse summary is a full-length local-model (Ollama) call that runs ~1-2 minutes on the Mini, so
 * the UI never leaves the user staring at a bare spinner: a progress bar fills over an adaptive ETA
 * (seeded at {@link DEFAULT_ETA_MS}, then learned from each refresh's real duration via localStorage)
 * and the button counts down the estimated time remaining. On load, a stale pulse ({@link STALE_MS})
 * silently re-refreshes so it's current shortly after login. Timestamps are absolute ("as of" time).
 * `undefined` = loading, `null` = none yet.
 */

/** Re-refresh a pulse this old (or older) automatically on load. Market news is intraday, so 4h. */
const STALE_MS = 4 * 60 * 60 * 1000;
/** First-run estimate for the model call, before we've learned a real duration. */
const DEFAULT_ETA_MS = 120_000;
/** Hard ceiling so a stuck call fails gracefully instead of spinning forever. */
const REFRESH_TIMEOUT_MS = 300_000;
const ETA_KEY = "argus.marketPulse.etaMs";

function readEta(): number {
  if (typeof window === "undefined") return DEFAULT_ETA_MS;
  const raw = Number(window.localStorage.getItem(ETA_KEY));
  return Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_ETA_MS;
}

/** Blend the new duration into the stored estimate (smoothed, clamped to a sane 30s–4m band). */
function writeEta(actualMs: number): void {
  if (typeof window === "undefined") return;
  const prev = readEta();
  const blended = Math.round(0.5 * prev + 0.5 * actualMs);
  const clamped = Math.min(240_000, Math.max(30_000, blended));
  window.localStorage.setItem(ETA_KEY, String(clamped));
}

function isStale(iso: string): boolean {
  return Date.now() - new Date(iso).getTime() >= STALE_MS;
}

/** "1:45 left" past a minute, "42s left" under it, or "Almost done…" once the estimate is spent. */
function remainingLabel(remainingMs: number): string {
  if (remainingMs <= 1000) return "Almost done…";
  const secs = Math.ceil(remainingMs / 1000);
  if (secs < 60) return `~${secs}s left`;
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return `~${m}:${String(s).padStart(2, "0")} left`;
}

export function BriefingCard() {
  const [briefing, setBriefing] = useState<Briefing | null | undefined>(undefined);
  const [pulse, setPulse] = useState<MarketPulse | null | undefined>(undefined);
  const [refreshing, setRefreshing] = useState(false);
  const [progress, setProgress] = useState(0); // 0..1 across the estimated duration
  const [remainingMs, setRemainingMs] = useState(0);
  const [note, setNote] = useState<string | null>(null);

  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const refreshingRef = useRef(false);

  const stopTick = useCallback(() => {
    if (tickRef.current) {
      clearInterval(tickRef.current);
      tickRef.current = null;
    }
  }, []);

  const onRefresh = useCallback(async () => {
    if (refreshingRef.current) return;
    refreshingRef.current = true;
    setRefreshing(true);
    setNote(null);
    setProgress(0);

    const started = Date.now();
    const eta = readEta();
    setRemainingMs(eta);
    stopTick();
    tickRef.current = setInterval(() => {
      const elapsed = Date.now() - started;
      setProgress(Math.min(elapsed / eta, 0.97)); // hold at 97% until the real response lands
      setRemainingMs(Math.max(eta - elapsed, 0));
    }, 200);

    const ac = new AbortController();
    const timeout = setTimeout(() => ac.abort(), REFRESH_TIMEOUT_MS);
    try {
      const next = await refreshMarketPulse(ac.signal);
      writeEta(Date.now() - started);
      setProgress(1);
      setPulse(next);
      if (!next.hasUpdates) {
        setNote("No major updates since we last checked.");
      }
    } catch {
      setNote("Couldn't refresh right now — try again in a moment.");
    } finally {
      clearTimeout(timeout);
      stopTick();
      refreshingRef.current = false;
      setRefreshing(false);
    }
  }, [stopTick]);

  // onRefresh is a stable useCallback (its only dep, stopTick, never changes), so this mount effect
  // runs once — referencing it directly won't re-trigger the load.
  useEffect(() => {
    let active = true;
    getLatestBriefing()
      .then((b) => active && setBriefing(b))
      .catch(() => active && setBriefing(null));
    getMarketPulse()
      .then((p) => {
        if (!active) return;
        setPulse(p);
        // Fresh-on-login: regenerate silently if we have nothing or it's gone stale.
        if (p === null || isStale(p.generatedAt)) {
          void onRefresh();
        }
      })
      .catch(() => active && setPulse(null));
    return () => {
      active = false;
      stopTick();
    };
  }, [onRefresh, stopTick]);

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
          Morning Briefing
        </h3>
        {briefing && (
          <span className="font-mono text-[10px] text-text-secondary">as of {absTime(briefing.generatedAt)}</span>
        )}
      </div>

      {briefing === undefined ? (
        <div className="mt-3 space-y-2">
          <div className="h-5 w-2/3 animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-full animate-pulse rounded-lg bg-border/40" />
          <div className="h-4 w-5/6 animate-pulse rounded-lg bg-border/40" />
        </div>
      ) : briefing === null ? (
        <div className="mt-3 flex items-center gap-2 py-2">
          <SunriseIcon />
          <p className="text-sm text-text-secondary">Your morning briefing will arrive at 8am.</p>
        </div>
      ) : (
        <div className="mt-2">
          <p className="font-serif-editorial text-lg font-normal leading-snug text-text-primary">
            {briefing.headline}
          </p>
          <p className="mt-2 text-sm leading-relaxed text-text-secondary">{briefing.body}</p>
        </div>
      )}

      <MarketPulseSection
        pulse={pulse}
        refreshing={refreshing}
        progress={progress}
        remainingMs={remainingMs}
        note={note}
        onRefresh={onRefresh}
      />
    </div>
  );
}

function MarketPulseSection({
  pulse,
  refreshing,
  progress,
  remainingMs,
  note,
  onRefresh,
}: {
  pulse: MarketPulse | null | undefined;
  refreshing: boolean;
  progress: number;
  remainingMs: number;
  note: string | null;
  onRefresh: () => void;
}) {
  return (
    <div className="mt-4 border-t border-border/50 pt-3">
      <div className="flex items-center justify-between">
        <div className="flex items-baseline gap-2">
          <h4 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
            Market Pulse
          </h4>
          {pulse && !refreshing && (
            <span className="font-mono text-[10px] text-text-secondary">as of {absTime(pulse.generatedAt)}</span>
          )}
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="flex items-center gap-1 rounded-md px-2 py-1 text-[11px] font-medium text-accent transition hover:bg-accent/[0.08] disabled:opacity-60"
        >
          <RefreshIcon spinning={refreshing} />
          {refreshing ? remainingLabel(remainingMs) : "Refresh"}
        </button>
      </div>

      {refreshing && (
        <div className="mt-2" aria-hidden>
          <div className="h-1 w-full overflow-hidden rounded-full bg-border/40">
            <div
              className="h-full rounded-full bg-accent transition-[width] duration-200 ease-linear"
              style={{ width: `${Math.round(progress * 100)}%` }}
            />
          </div>
          <p className="mt-1.5 text-[11px] text-text-secondary/80">
            Reading the market-impacting news and summarizing…
          </p>
        </div>
      )}

      {refreshing ? null : pulse === undefined ? (
        <div className="mt-2 space-y-2">
          <div className="h-3.5 w-full animate-pulse rounded bg-border/40" />
          <div className="h-3.5 w-4/5 animate-pulse rounded bg-border/40" />
        </div>
      ) : pulse === null ? (
        <p className="mt-2 text-sm text-text-secondary">
          Tap Refresh to summarize the market-impacting news captured so far.
        </p>
      ) : (
        <p className="mt-2 text-sm leading-relaxed text-text-secondary">{pulse.summary}</p>
      )}

      {note && <p className="mt-2 text-[11px] italic text-text-secondary/80">{note}</p>}
    </div>
  );
}

function SunriseIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="text-text-secondary"
      aria-hidden
    >
      <path d="M12 2v5" />
      <path d="m4.93 10.93 1.41 1.41" />
      <path d="M2 18h2" />
      <path d="M20 18h2" />
      <path d="m19.07 10.93-1.41 1.41" />
      <path d="M22 22H2" />
      <path d="m16 6-4 4-4-4" />
      <path d="M16 18a4 4 0 0 0-8 0" />
    </svg>
  );
}

function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={spinning ? "animate-spin" : ""}
      aria-hidden
    >
      <path d="M21 12a9 9 0 1 1-3-6.7" />
      <path d="M21 3v5h-5" />
    </svg>
  );
}
