"use client";

import { useEffect, useState } from "react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getStorage, type AgentStorage, type StorageView } from "@/lib/apiClient";

/**
 * Data storage by agent (Ops) — how much each agent has accumulated and where it lives. Shows the
 * total database footprint, then each agent's share (rows + on-disk size) with a per-table breakdown
 * so you can see exactly which Postgres table holds what.
 */
export function DataStorage() {
  const [view, setView] = useState<StorageView | null>(null);
  const [error, setError] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let active = true;
    getStorage()
      .then((v) => active && setView(v))
      .catch(() => active && setError(true))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, []);

  if (!loaded) return <Skeleton className="h-72" />;
  if (error || !view) {
    return (
      <MotionCard index={0} interactive={false}>
        <p className="text-sm text-text-secondary">Storage details are unavailable right now.</p>
      </MotionCard>
    );
  }

  const max = Math.max(1, ...view.agents.map((a) => a.bytes));

  return (
    <MotionCard index={0} interactive={false} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">Data storage by agent</h3>
          <p className="mt-0.5 text-xs text-text-secondary">
            Stored in PostgreSQL (<span className="font-mono">{view.database}</span>) on the Mac Mini.
          </p>
        </div>
        <div className="text-right">
          <p className="font-mono text-lg font-semibold tabular-nums text-text-primary">
            {formatBytes(view.totalBytes)}
          </p>
          <p className="text-[11px] text-text-secondary">{formatNum(view.totalRows)} rows total</p>
        </div>
      </div>

      <ul className="flex flex-col divide-y divide-[var(--hairline)]">
        {view.agents.map((a) => (
          <AgentRow key={a.key} agent={a} max={max} />
        ))}
      </ul>
    </MotionCard>
  );
}

function AgentRow({ agent, max }: { agent: AgentStorage; max: number }) {
  const pct = Math.max(2, Math.round((agent.bytes / max) * 100));
  return (
    <li className="py-3">
      <details className="group">
        <summary className="flex cursor-pointer list-none items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-text-primary">{agent.name}</p>
            <p className="truncate text-[11px] text-text-secondary">{agent.description}</p>
          </div>
          <div className="flex shrink-0 items-center gap-2 text-right">
            <div>
              <p className="font-mono text-sm tabular-nums text-text-primary">{formatBytes(agent.bytes)}</p>
              <p className="text-[10px] text-text-secondary">{formatNum(agent.rows)} rows</p>
            </div>
            <svg
              className="h-3.5 w-3.5 text-text-secondary transition-transform group-open:rotate-90"
              viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden
            >
              <path d="m9 18 6-6-6-6" />
            </svg>
          </div>
        </summary>

        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-[var(--hairline)]">
          <div className="h-full rounded-full bg-accent" style={{ width: `${pct}%` }} />
        </div>

        <table className="mt-3 w-full text-left text-xs">
          <thead>
            <tr className="text-[10px] uppercase tracking-wider text-text-secondary">
              <th className="pb-1 font-medium">Stores</th>
              <th className="pb-1 pl-2 font-medium">Table (where)</th>
              <th className="pb-1 pl-2 text-right font-medium">Rows</th>
              <th className="pb-1 pl-2 text-right font-medium">Size</th>
            </tr>
          </thead>
          <tbody className="text-text-secondary">
            {agent.tables.map((t) => (
              <tr key={t.table} className="border-t border-[var(--hairline)]/60">
                <td className="py-1 pr-2">
                  <span className="text-text-primary">{t.label}</span>
                  <span className="block text-[10px] text-text-secondary/80">{t.stores}</span>
                </td>
                <td className="py-1 pl-2 align-top font-mono text-[11px]">{t.table}</td>
                <td className="py-1 pl-2 text-right align-top font-mono tabular-nums">{formatNum(t.rows)}</td>
                <td className="py-1 pl-2 text-right align-top font-mono tabular-nums">{formatBytes(t.bytes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </li>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

function formatNum(n: number): string {
  return n.toLocaleString("en-US");
}
