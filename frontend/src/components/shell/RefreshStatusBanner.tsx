"use client";

import { useSyncExternalStore } from "react";
import { snapshot, staleMessage, subscribe } from "@/lib/refreshHealth";

const serverSnapshot = () => snapshot();

/**
 * S-D3 — says so when the app's background refreshes are failing, instead of silently keeping stale numbers on
 * screen. Hidden while refreshes succeed; appears after a network failure or a server error with when the data
 * on screen was last good, and clears itself on the next successful read.
 */
export function RefreshStatusBanner() {
  const health = useSyncExternalStore(subscribe, snapshot, serverSnapshot);
  const message = staleMessage(health, (ms) =>
    new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }),
  );
  if (!message) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      className="flex shrink-0 items-center gap-2 border-b border-warning/40 bg-warning/[0.08] px-4 py-1.5 font-mono text-[11px] text-warning"
    >
      <span aria-hidden className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-warning motion-reduce:animate-none" />
      {message}
    </div>
  );
}
