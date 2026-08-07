"use client";

import { useCallback, useEffect, useState } from "react";
import { motion, useReducedMotion } from "motion/react";

import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { ApiError, getResearchJobs, startResearch, type ResearchJobView } from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { ResearchJobDetail } from "./ResearchJobDetail";

/**
 * Agent 9 — on-demand research (Agents page). Unlike every other agent, this one only works when
 * asked: type a ticker, it plans its own research, works through the plan live, and writes a report.
 * The trigger + job history live here; the live plan/progress/report view is {@link ResearchJobDetail}.
 */
export function ResearchAgentSection() {
  const [jobs, setJobs] = useState<ResearchJobView[] | null>(null);
  const [expandedId, setExpandedId] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    getResearchJobs()
      .then((j) => active && setJobs(j))
      .catch(() => active && setJobs([]));
    return () => {
      active = false;
    };
  }, []);

  const onStarted = useCallback((job: ResearchJobView) => {
    setJobs((prev) => [job, ...(prev ?? [])]);
    setExpandedId(job.id);
  }, []);

  // A job's own detail view owns its live WebSocket subscription; it reports every update back up
  // here so the collapsed row (ticker/status/time) never goes stale while expanded.
  const onJobUpdate = useCallback((updated: ResearchJobView) => {
    setJobs((prev) => (prev ? prev.map((j) => (j.id === updated.id ? updated : j)) : prev));
  }, []);

  return (
    <div>
      <h2 className="mb-1 font-display text-lg font-semibold text-text-primary">
        On-Demand Research · Agent 9
      </h2>
      <p className="mb-4 text-sm text-text-secondary">
        Every other agent runs on a schedule — this one only works when you ask. Name a ticker and it
        plans its own research, gathers news, macro, social, insider, web, and earnings data, revises
        the plan mid-run if it learns something new, then writes a long-term/short-term analysis.
      </p>

      <MotionCard index={0} interactive={false} entrance="fade" className="flex flex-col gap-4">
        <ResearchTrigger onStarted={onStarted} />

        {jobs === null ? (
          <Skeleton className="h-24" />
        ) : jobs.length === 0 ? (
          <p className="py-6 text-center text-xs text-text-secondary">
            No research yet — ask above and watch it work.
          </p>
        ) : (
          <ul className="flex flex-col divide-y divide-border/60">
            {jobs.map((job) => (
              <li key={job.id} className="py-3">
                <button
                  type="button"
                  onClick={() => setExpandedId((id) => (id === job.id ? null : job.id))}
                  className="flex w-full items-center justify-between gap-3 text-left"
                >
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-semibold text-text-primary">{job.ticker}</span>
                    <StatusPill status={job.status} />
                  </div>
                  <span className="text-[11px] text-text-secondary">
                    {new Date(job.createdAt).toLocaleString()}
                  </span>
                </button>

                {expandedId === job.id && (
                  <div className="mt-3 border-t border-border/60 pt-3">
                    <ResearchJobDetail initial={job} onUpdate={onJobUpdate} />
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </MotionCard>
    </div>
  );
}

function ResearchTrigger({ onStarted }: { onStarted: (job: ResearchJobView) => void }) {
  const [ticker, setTicker] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reduce = useReducedMotion();

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const trimmed = ticker.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const job = await startResearch(trimmed);
      setTicker("");
      onStarted(job);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't start research — try again in a moment.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-1.5">
      <form onSubmit={onSubmit} className="flex items-center gap-2">
        <input
          value={ticker}
          onChange={(e) => setTicker(e.target.value)}
          placeholder="Research a stock — try a ticker like SPCX"
          disabled={busy}
          maxLength={10}
          className="flex-1 rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text-primary placeholder:text-text-secondary/60 disabled:opacity-60"
        />
        <motion.button
          type="submit"
          disabled={busy || !ticker.trim()}
          whileTap={!reduce && !busy ? { scale: 0.96 } : undefined}
          className="shrink-0 rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {busy ? "Starting…" : "Research"}
        </motion.button>
      </form>
      {error && <p className="text-[11px] text-losses">{error}</p>}
    </div>
  );
}

function StatusPill({ status }: { status: ResearchJobView["status"] }) {
  const map: Record<ResearchJobView["status"], { label: string; cls: string }> = {
    PLANNING: { label: "Planning", cls: "bg-accent/10 text-accent" },
    RESEARCHING: { label: "Researching", cls: "bg-accent/10 text-accent" },
    REVISING_PLAN: { label: "Revising plan", cls: "bg-warning/10 text-warning" },
    SYNTHESIZING: { label: "Writing report", cls: "bg-accent/10 text-accent" },
    DONE: { label: "Done", cls: "bg-gains/10 text-gains" },
    FAILED: { label: "Failed", cls: "bg-losses/10 text-losses" },
  };
  const s = map[status];
  const inProgress = status !== "DONE" && status !== "FAILED";
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide", s.cls)}>
      {inProgress && <span className="relative flex h-1.5 w-1.5">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-75" />
        <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-current" />
      </span>}
      {s.label}
    </span>
  );
}
