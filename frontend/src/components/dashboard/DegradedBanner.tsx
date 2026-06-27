"use client";

import { useEffect, useState } from "react";

import { getPlatformMode, type PlatformModeView } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";

/**
 * Degraded Mode banner (Epic 10, Story 10.4). Shows when the backend reports the platform is offline,
 * so the dashboard's last-known values read as intentionally stale rather than broken. Updates live via
 * /topic/platform-mode and renders nothing in NORMAL mode.
 */
export function DegradedBanner() {
  const [mode, setMode] = useState<PlatformModeView | null>(null);

  useEffect(() => {
    let active = true;
    getPlatformMode()
      .then((v) => active && setMode(v))
      .catch(() => {});
    const sub = subscribeToTopic<PlatformModeView>("/topic/platform-mode", (v) => active && setMode(v));
    return () => {
      active = false;
      sub.disconnect();
    };
  }, []);

  if (!mode || mode.mode !== "DEGRADED") return null;

  return (
    <div
      role="alert"
      className="mb-4 flex items-center gap-3 rounded-xl border border-warning/30 bg-warning/[0.08] px-4 py-3"
    >
      <span className="relative flex h-2 w-2 shrink-0">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-warning opacity-75" />
        <span className="relative inline-flex h-2 w-2 rounded-full bg-warning" />
      </span>
      <div className="min-w-0">
        <p className="text-sm font-semibold text-warning">Offline — showing last-known data</p>
        <p className="text-xs text-text-secondary">
          {mode.reason}. Live feeds are paused; local analysis still works. Argus will catch up on reconnect.
        </p>
      </div>
    </div>
  );
}
