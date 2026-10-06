"use client";

import { useEffect, useRef, type DependencyList } from "react";

/** Common refresh cadences, matched to how fast each kind of data actually changes server-side. */
export const REFRESH = {
  /** Alerts, agent health, live job lists. */
  FAST: 60_000,
  /** Recommendations, portfolio-derived views, watchlists — the default. */
  NORMAL: 5 * 60_000,
  /** Daily/nightly products: fundamentals, scorecards, learning reports, storage. */
  SLOW: 15 * 60_000,
} as const;

/** Coming back to a tab sooner than this after the last load doesn't re-fetch. */
const MIN_GAP_MS = 15_000;

/**
 * Keeps a component's data fresh: runs `load` on mount (and when `deps` change), again every
 * `intervalMs` while the page is visible, and immediately when the page becomes visible again or the
 * window regains focus — a phone unlocked or a laptop woken up after hours must never keep showing
 * what it loaded before. A hidden tab doesn't poll. `load` should keep the current data on a failed
 * refresh (only a failed *first* load falls back to empty), so a network blip never blanks a panel.
 */
export function useAutoRefresh(load: () => unknown, intervalMs: number = REFRESH.NORMAL, deps: DependencyList = []) {
  const latest = useRef(load);
  useEffect(() => {
    latest.current = load;
  });

  useEffect(() => {
    let last = 0;
    const run = () => {
      last = Date.now();
      void latest.current();
    };
    run();
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") run();
    }, intervalMs);
    const onReturn = () => {
      if (document.visibilityState === "visible" && Date.now() - last > MIN_GAP_MS) run();
    };
    document.addEventListener("visibilitychange", onReturn);
    window.addEventListener("focus", onReturn);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onReturn);
      window.removeEventListener("focus", onReturn);
    };
    // `deps` is the caller's own dependency list (e.g. a selected range); `load` is read via the ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, ...deps]);
}
