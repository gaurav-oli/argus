"use client";

import { useEffect, useState } from "react";
import { getOpsSummary, type OpsSummary } from "@/lib/apiClient";

/**
 * Bottom strip — real ops telemetry (Epic 9): agents active + cumulative paid (Haiku) spend, from
 * /api/ops/summary. Host RAM/SSD telemetry is the Hardware Resource Monitor (Story 9.5) and isn't
 * surfaced yet, so it's omitted rather than shown blank. Desktop only (hidden below `lg`).
 */
export function BottomStrip() {
  const [ops, setOps] = useState<OpsSummary | null>(null);

  useEffect(() => {
    let active = true;
    const load = () => getOpsSummary().then((v) => active && setOps(v)).catch(() => {});
    load();
    const id = setInterval(load, 30_000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, []);

  const live = (ops?.agentsActive ?? 0) > 0;

  return (
    <footer className="hidden h-9 shrink-0 items-center gap-6 border-t border-[var(--glass-border)] glass-chrome px-6 font-mono text-xs text-text-secondary lg:flex">
      <span className="flex items-center gap-2">
        <span
          className="inline-block h-2 w-2 rounded-full"
          style={{
            backgroundColor: live ? "var(--color-gains)" : "var(--color-text-secondary)",
            boxShadow: live ? "0 0 8px var(--color-gains)" : undefined,
          }}
          aria-hidden
        />
        agents: {ops ? `${ops.agentsActive}/${ops.agentsTotal}` : "—"}
      </span>
      <span>
        Haiku spend: {ops ? `$${ops.haikuSpendUsd.toFixed(4)}` : "—"}
      </span>
      <span className="ml-auto text-text-secondary/70">Argus · live</span>
    </footer>
  );
}
