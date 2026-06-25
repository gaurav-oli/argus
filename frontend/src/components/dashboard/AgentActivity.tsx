"use client";

import { motion, useReducedMotion } from "motion/react";

/** A single agent rendered in the pipeline hero. */
export type PipelineAgent = {
  id: string;
  name: string;
  code: string;
  active: boolean;
  /** Short metric shown at the end of the wire, e.g. a capture count. */
  metric: string;
};

/**
 * Live view of the agent fleet: each agent streams signal particles down a wire into the central
 * Argus core. Active agents pulse + flow; idle agents are dim. Driven by real per-agent status
 * (Epic 9, Story 9.1) passed in from {@link AgentFleet}.
 */
export function AgentActivity({ agents }: { agents: PipelineAgent[] }) {
  const reduce = useReducedMotion();
  const liveCount = agents.filter((a) => a.active).length;

  return (
    <div>
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">
          Agent Pipeline
        </h3>
        <span className="flex items-center gap-1.5 text-[11px] text-text-secondary">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gains opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-gains" />
          </span>
          {liveCount} live
        </span>
      </div>

      <div className="mt-4 flex items-stretch gap-3">
        {/* agents + wires */}
        <div className="flex-1 space-y-3">
          {agents.map((a, i) => {
            const on = a.active;
            return (
              <div key={a.id} className="flex items-center gap-3">
                <div className="relative flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-border bg-[var(--hover-wash)]">
                  {on && !reduce && (
                    <span className="absolute inset-0 rounded-xl ring-1 ring-accent/50">
                      <span className="absolute inset-0 animate-ping rounded-xl bg-accent/10" />
                    </span>
                  )}
                  <span className={`font-mono text-xs font-bold ${on ? "text-accent" : "text-text-secondary"}`}>
                    {a.code.replace(/[^0-9]/g, "") || a.name.slice(0, 2).toUpperCase()}
                  </span>
                </div>

                <div className="w-28 shrink-0">
                  <p className={`truncate text-sm font-medium ${on ? "text-text-primary" : "text-text-secondary"}`}>
                    {a.name}
                  </p>
                  <p className="font-mono text-[10px] text-text-secondary">{a.code}</p>
                </div>

                {/* flowing wire */}
                <div className="relative h-px flex-1 bg-gradient-to-r from-[var(--hairline)] via-[var(--hairline)] to-accent/30">
                  {on &&
                    !reduce &&
                    [0, 1, 2].map((k) => (
                      <motion.span
                        key={k}
                        className="absolute top-1/2 h-1.5 w-1.5 -translate-y-1/2 rounded-full bg-accent"
                        style={{ boxShadow: "0 0 6px var(--chart-accent)" }}
                        initial={{ left: "0%", opacity: 0 }}
                        animate={{ left: ["0%", "100%"], opacity: [0, 1, 1, 0] }}
                        transition={{ duration: 1.8, delay: k * 0.6 + i * 0.2, repeat: Infinity, ease: "linear" }}
                      />
                    ))}
                </div>

                <span className="w-16 shrink-0 text-right font-mono text-[11px] tabular-nums text-text-secondary">
                  {on ? a.metric : "idle"}
                </span>
              </div>
            );
          })}
        </div>

        {/* core */}
        <div className="flex items-center">
          <div className="relative flex h-20 w-20 flex-col items-center justify-center rounded-2xl border border-accent/30 bg-accent/[0.06]">
            {!reduce && <span className="absolute inset-0 animate-ping rounded-2xl bg-accent/5" />}
            <span className="h-3 w-3 rounded-full bg-accent" style={{ boxShadow: "0 0 16px var(--chart-accent)" }} />
            <span className="mt-1.5 text-[10px] font-semibold uppercase tracking-wider text-accent">Argus</span>
            <span className="text-[9px] text-text-secondary">core</span>
          </div>
        </div>
      </div>
    </div>
  );
}
