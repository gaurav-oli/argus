"use client";

import { AgentActivity, type PipelineAgent } from "@/components/dashboard/AgentActivity";
import { AgentDossiers } from "@/features/agents/AgentDossiers";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import {
  getAgentStatus,
  getBudgetStatus,
  triggerMacroKeywordReview,
  type AgentStatus,
  type BudgetStatus,
  type MacroKeywordReviewResult,
} from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { subscribeToTopic } from "@/lib/wsClient";
import { useCallback, useEffect, useState } from "react";

/**
 * Agents dashboard (Epic 9, Story 9.1). Live per-agent status from /api/agents/status: what each
 * agent does, whether it's running, how much it has captured, and when it last ran — plus the
 * pipeline hero visualization. Read-only; data is produced by the backend agents.
 */
export function AgentFleet() {
  const [agents, setAgents] = useState<AgentStatus[] | null>(null);
  const [budget, setBudget] = useState<BudgetStatus | null>(null);

  useEffect(() => {
    let active = true;
    getAgentStatus()
      .then((v) => active && setAgents(v))
      .catch(() => active && setAgents([]));
    getBudgetStatus()
      .then((v) => active && setBudget(v))
      .catch(() => {});

    // Story 9.1 — live updates: the backend pushes the fleet snapshot to /topic/agents on an interval.
    const sub = subscribeToTopic<AgentStatus[]>("/topic/agents", (v) => active && setAgents(v));

    return () => {
      active = false;
      sub.disconnect();
    };
  }, []);

  if (agents === null) return <FleetSkeleton />;

  const online = agents.filter((a) => a.status === "ACTIVE").length;
  const totalCaptured = agents.reduce((sum, a) => sum + a.captured, 0);
  const lastActivity = agents
    .map((a) => a.lastActivity)
    .filter((x): x is string => Boolean(x))
    .sort()
    .at(-1);

  const pipeline: PipelineAgent[] = agents.map((a) => ({
    id: a.id,
    name: a.name,
    code: a.code,
    status: a.status,
    lastActivity: a.lastActivity,
    intervalMinutes: a.intervalMinutes ?? null,
    staleAfterMinutes: a.staleAfterMinutes ?? null,
    schedule: a.schedule,
  }));

  return (
    <div className="flex flex-col gap-5">
      {/* Summary band */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatTile
          label="Agents online"
          value={`${online}`}
          suffix={`/ ${agents.length}`}
          live={online > 0}
        />
        <StatTile
          label="Items captured"
          value={<AnimatedNumber value={totalCaptured} format={(n) => compact(Math.round(n))} />}
          suffix="total"
        />
        <StatTile
          label="Last activity"
          value={relTime(lastActivity ?? null)}
          className="col-span-2 lg:col-span-1"
        />
      </div>

      {/* Cost Governor budget panel (Agent 6) */}
      {budget && <BudgetPanel b={budget} />}

      {/* Pipeline hero */}
      <MotionCard index={0} interactive={false} entrance="fade">
        <AgentActivity agents={pipeline} />
      </MotionCard>

      {/* What each agent is doing — dossiers filed by stream (same buckets as the pipeline) */}
      <AgentDossiers agents={agents} back={{ macro: <MacroKeywordReviewButton /> }} />
    </div>
  );
}

function BudgetPanel({ b }: { b: BudgetStatus }) {
  const color =
    b.band === "CRITICAL"
      ? "var(--color-losses)"
      : b.band === "WARNING"
        ? "var(--color-warning)"
        : b.band === "NOTICE"
          ? "var(--color-accent)"
          : "var(--color-gains)";
  const pct = Math.min(100, b.percentUsed);
  const usd = (n: number) => `$${n.toFixed(n < 1 ? 4 : 2)}`;
  return (
    <MotionCard index={0} interactive={false} entrance="fade" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">
            Cost Governor · Agent 6
          </p>
          <p className="mt-1 font-display text-xl font-bold text-text-primary">
            {usd(b.spentUsd)} <span className="text-sm font-normal text-text-secondary">of {usd(b.budgetUsd)} this month</span>
          </p>
        </div>
        <span
          className="rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
          style={{ color, backgroundColor: `color-mix(in srgb, ${color} 14%, transparent)` }}
        >
          {b.band}
        </span>
      </div>

      {/* progress with threshold markers at 70 / 80 / 95 */}
      <div className="relative h-2.5 w-full overflow-hidden rounded-full bg-[var(--hairline)]">
        <div className="h-full rounded-full transition-all" style={{ width: `${pct}%`, backgroundColor: color }} />
        {[70, 80, 95].map((m) => (
          <span key={m} className="absolute top-0 h-full w-px bg-text-secondary/40" style={{ left: `${m}%` }} />
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-text-secondary">
        <span>
          Projected <span className="font-mono text-text-primary">{usd(b.projectedUsd)}</span>
        </span>
        <span>
          {b.paidCalls} paid call{b.paidCalls === 1 ? "" : "s"}
        </span>
        <span>
          {b.localModelCalls} Gemma call{b.localModelCalls === 1 ? "" : "s"}
        </span>
        <span>{b.daysLeftInMonth}d left in {b.month}</span>
        {b.paidCallsBlocked && (
          <span className="ml-auto font-semibold text-losses">⚠ Budget reached — escalations on local model</span>
        )}
      </div>
    </MotionCard>
  );
}

function StatTile({
  label,
  value,
  suffix,
  live,
  className,
}: {
  label: string;
  value: React.ReactNode;
  suffix?: string;
  live?: boolean;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "rounded-xl border border-[var(--hairline)] bg-gradient-to-b from-surface to-surface/60 px-4 py-3",
        className,
      )}
    >
      <div className="flex items-center gap-1.5">
        {live && (
          <span className="relative flex h-1.5 w-1.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-gains opacity-75" />
            <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-gains" />
          </span>
        )}
        <span className="text-[10px] font-medium uppercase tracking-wider text-text-secondary">{label}</span>
      </div>
      <p className="mt-1 flex items-baseline gap-1.5">
        <span className="font-display text-2xl font-bold tabular-nums text-text-primary">{value}</span>
        {suffix && <span className="text-xs text-text-secondary">{suffix}</span>}
      </p>
    </div>
  );
}

/**
 * Manual trigger for Agent 8's weekly keyword-learning review (MacroKeywordLearningService) — rather
 * than waiting for the Sunday cron. Lives on the cream back of Agent 8's dossier, hence the ink colours. Shows the resulting reason inline: what it considered, and
 * whether anything was actually learned.
 */
function MacroKeywordReviewButton() {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<MacroKeywordReviewResult | null>(null);
  const [failed, setFailed] = useState(false);

  const onReview = useCallback(async () => {
    setBusy(true);
    setFailed(false);
    try {
      const r = await triggerMacroKeywordReview();
      setResult(r);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }, []);

  return (
    <div className="mt-2 flex flex-col gap-1">
      <button
        type="button"
        onClick={onReview}
        disabled={busy}
        className="self-start border border-[#23221d] px-2 py-0.5 font-mono text-[10px] font-bold text-[#23221d] transition-colors hover:bg-[#23221d] hover:text-[#efe8d4] disabled:cursor-not-allowed disabled:opacity-50"
      >
        {busy ? "Reviewing…" : "Review Now"}
      </button>
      {result && (
        <p className="text-[10px] leading-snug text-[#5c5a50]">
          {result.adopted.length > 0 && (
            <span className="mr-1 font-semibold text-[#2f6b3a]">
              +{result.adopted.length} keyword{result.adopted.length === 1 ? "" : "s"} learned:
            </span>
          )}
          {result.reason}
        </p>
      )}
      {failed && <p className="text-[10px] text-[#b42318]">Couldn&apos;t run the review — try again in a moment.</p>}
    </div>
  );
}





function FleetSkeleton() {
  return (
    <div className="flex flex-col gap-5">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className={cn("h-[68px]", i === 2 && "col-span-2 lg:col-span-1")} />
        ))}
      </div>
      <Skeleton className="h-56" />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {[0, 1, 2, 3, 4, 5].map((i) => (
          <Skeleton key={i} className="h-44" />
        ))}
      </div>
    </div>
  );
}

/** Relative "time ago" for a capture timestamp. */
function relTime(iso: string | null): string {
  if (!iso) return "never";
  const ms = Date.now() - new Date(iso).getTime();
  const m = Math.floor(ms / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(iso).toLocaleDateString("en-CA", { month: "short", day: "numeric" });
}

/** Compact number for the summary/pipeline (e.g. 1.2k). */
function compact(n: number): string {
  return Intl.NumberFormat("en-CA", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}
