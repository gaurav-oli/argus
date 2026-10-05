"use client";

import { useEffect, useState } from "react";
import { getAgentStatus, type AgentStatus } from "@/lib/apiClient";

const MAX_LINES = 10;

function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-CA", { hour12: false });
}

/**
 * Terminal Noir `tail -f` of the agent fleet — each agent's most recent run as a log line, newest at
 * the bottom, re-polled every 15s. A line is keyed by agent + run time, so when an agent reports
 * again its new line animates in while unchanged lines stay put. Real data only (`/api/agents/
 * status`); agents that have never run are left out rather than shown as fake activity.
 */
export function AgentLogTail() {
  const [agents, setAgents] = useState<AgentStatus[] | null>(null);

  useEffect(() => {
    let active = true;
    const load = () =>
      getAgentStatus()
        .then((a) => active && setAgents(a))
        .catch(() => active && setAgents((prev) => prev ?? []));
    load();
    const id = setInterval(load, 15_000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, []);

  const lines = (agents ?? [])
    .filter((a): a is AgentStatus & { lastActivity: string } => a.lastActivity != null)
    .sort((a, b) => a.lastActivity.localeCompare(b.lastActivity))
    .slice(-MAX_LINES);
  const up = (agents ?? []).filter((a) => a.status === "ACTIVE" || a.status === "IDLE").length;

  return (
    <section className="glass mt-4 p-4 font-mono text-xs" aria-label="Agent activity log">
      <h3 className="mb-3 text-[11px]">
        agent log · tail -f · {agents ? `${up}/${agents.length} up` : "connecting"}
      </h3>
      {agents === null ? (
        <p className="text-text-secondary">
          waiting for the fleet<span className="term-caret" aria-hidden />
        </p>
      ) : lines.length === 0 ? (
        <p className="text-text-secondary">no agent has reported yet.</p>
      ) : (
        <ol className="space-y-1" aria-live="polite">
          {lines.map((a) => (
            <li key={`${a.name}-${a.lastActivity}`} className="term-line-in grid grid-cols-[4.5rem_minmax(0,10rem)_1fr] gap-3">
              <span className="text-text-secondary tabular-nums">{clock(a.lastActivity)}</span>
              <span className="truncate text-accent">{a.name}</span>
              <span className="truncate text-text-primary">
                {a.captured.toLocaleString()} {a.captureLabel}
                {a.status === "PARTIAL" && <span className="text-warning"> · partial</span>}
              </span>
            </li>
          ))}
        </ol>
      )}
      <p className="mt-2 text-accent" aria-hidden>
        $<span className="term-caret" />
      </p>
    </section>
  );
}
