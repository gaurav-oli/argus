"use client";

import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";
import type { DeepRunStatus } from "@/lib/apiClient";

/**
 * Agent 11's fixed stage sequence, in the exact order {@code DeepAnalystService} runs them. "Chart
 * technician" and "Fundamental analyst" are sometimes skipped for a ticker with no usable chart or
 * financials — the backend only reports where the pipeline is *now*, not which stages it plans to skip,
 * so a skipped stage is simply inferred done once the pipeline has moved past it.
 */
const STAGES = [
  "Gathering evidence from the other agents",
  "Chart technician reading the candles",
  "Fundamental analyst reading the financials",
  "News & catalyst analyst",
  "Macro & sector strategist",
  "Skeptic challenging the consensus",
  "Portfolio manager weighing the evidence",
  "Applying deterministic guardrails",
] as const;

/**
 * A live checklist of Agent 11's pipeline, built entirely from the one `stage` string the backend
 * already persists on every transition and {@code DeepAnalysisForTicker} already polls every 5s — no
 * backend change needed. Falls back to the old one-line status for a stage this list doesn't recognise
 * (queued, or a future backend change), so an unanticipated label never shows a dead end.
 */
export function DeepAnalysisPipeline({ status }: { status: DeepRunStatus }) {
  const reduce = useReducedMotion();
  const idx = STAGES.indexOf((status.stage ?? "") as (typeof STAGES)[number]);

  if (status.status === "QUEUED" || idx === -1) {
    return (
      <p className="rounded-lg bg-accent/10 px-3 py-2 text-xs text-accent">
        ⏳ {status.status === "QUEUED" ? "Queued" : "Working"}: {status.stage ?? "starting"} — a full analysis can take
        several minutes to hours.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-1.5 rounded-xl border border-border bg-surface p-3">
      <p className="mb-0.5 text-[10px] font-medium uppercase tracking-wide text-text-secondary">
        Agent 11 is working — this refreshes itself every few seconds
      </p>
      {STAGES.map((label, i) => {
        const done = i < idx;
        const running = i === idx;
        return (
          <motion.div
            key={label}
            className="flex items-center gap-2.5"
            initial={reduce ? false : { opacity: 0, x: -6 }}
            animate={{ opacity: 1, x: 0 }}
            transition={{ duration: 0.25, delay: reduce ? 0 : i * 0.03 }}
          >
            <div
              className={cn(
                "relative flex h-6 w-6 shrink-0 items-center justify-center rounded-full border",
                running ? "border-accent/40 bg-accent/[0.08]" : done ? "border-gains/30 bg-gains/[0.08]" : "border-border bg-[var(--hover-wash)]",
              )}
            >
              {running && (
                <span className="absolute inset-0 rounded-full ring-1 ring-accent/40">
                  {!reduce && <span className="absolute inset-0 animate-ping rounded-full bg-accent/15" />}
                </span>
              )}
              {done ? (
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" className="text-gains" aria-hidden>
                  <motion.path d="M20 6 9 17l-5-5" initial={reduce ? false : { pathLength: 0 }} animate={{ pathLength: 1 }} transition={{ duration: 0.3, ease: "easeOut" }} />
                </svg>
              ) : (
                <span className={cn("h-1.5 w-1.5 rounded-full", running ? "bg-accent" : "bg-text-secondary/40")} />
              )}
            </div>
            <span className={cn("text-xs", running ? "font-medium text-accent" : done ? "text-text-secondary" : "text-text-secondary/60")}>
              {label}
            </span>
          </motion.div>
        );
      })}
    </div>
  );
}
