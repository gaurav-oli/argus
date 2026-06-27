"use client";

import { AgentActivity, type PipelineAgent } from "@/components/dashboard/AgentActivity";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { MotionCard } from "@/components/ui/MotionCard";
import { Skeleton } from "@/components/ui/Skeleton";
import { getAgentStatus, getBudgetStatus, type AgentStatus, type BudgetStatus } from "@/lib/apiClient";
import { cn } from "@/lib/utils";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useState } from "react";

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
    active: a.status === "ACTIVE",
    metric: compact(a.captured),
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

      {/* Agent detail cards */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
        {agents.map((a, i) => (
          <AgentCard key={a.id} agent={a} index={i + 1} />
        ))}
      </div>
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

function AgentCard({ agent, index }: { agent: AgentStatus; index: number }) {
  const active = agent.status === "ACTIVE";
  const planned = agent.status === "PLANNED";
  const partial = agent.status === "PARTIAL";
  const num = agent.code.replace(/[^0-9]/g, "");

  return (
    <MotionCard index={index} interactive={!planned} className={cn("flex flex-col gap-4", planned && "opacity-60")}>
      {/* status accent line */}
      <span
        className={cn(
          "absolute inset-x-0 top-0 h-px",
          active ? "bg-gradient-to-r from-transparent via-accent/60 to-transparent" : "bg-[var(--hairline)]",
        )}
      />

      <div className="flex items-start gap-3">
        {/* monogram */}
        <div
          className={cn(
            "relative flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border",
            active ? "border-accent/30 bg-accent/[0.07]" : "border-border bg-[var(--hover-wash)]",
          )}
        >
          {active && (
            <span className="absolute inset-0 rounded-xl ring-1 ring-accent/40">
              <span className="absolute inset-0 animate-ping rounded-xl bg-accent/5" />
            </span>
          )}
          <span className={cn("font-mono text-base font-bold", active ? "text-accent" : "text-text-secondary")}>
            {num || agent.name.slice(0, 2).toUpperCase()}
          </span>
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="font-mono text-[10px] uppercase tracking-wider text-text-secondary">{agent.code}</span>
            <StatusPill status={agent.status} />
          </div>
          <h3 className="mt-0.5 truncate font-display text-base font-semibold text-text-primary">{agent.name}</h3>
        </div>
      </div>

      <p className="text-sm leading-relaxed text-text-secondary">{agent.description}</p>

      {/* metrics */}
      {planned ? (
        <div className="flex items-center justify-between border-t border-[var(--hairline)] pt-3.5 text-xs text-text-secondary">
          <span>Not yet built — on the roadmap.</span>
          <Chip>{agent.phase ?? "Planned"}</Chip>
        </div>
      ) : (
        <div className="flex items-end justify-between border-t border-[var(--hairline)] pt-3.5">
          <div>
            {partial ? (
              <p className="font-display text-lg font-semibold text-text-primary">{agent.note ?? "Subsystem"}</p>
            ) : (
              <>
                <p className="flex items-baseline gap-1.5">
                  <AnimatedNumber
                    value={agent.captured}
                    format={(n) => Math.round(n).toLocaleString("en-CA")}
                    className="font-display text-2xl font-bold tabular-nums text-text-primary"
                  />
                  <span className="text-xs text-text-secondary">{agent.captureLabel}</span>
                </p>
                <p className="mt-1 flex items-center gap-1.5 text-xs text-text-secondary">
                  <ClockIcon />
                  <span>
                    last run <span className="text-text-primary">{relTime(agent.lastActivity)}</span>
                  </span>
                </p>
              </>
            )}
          </div>
          <Chip>{partial ? agent.phase ?? "MVP" : agent.schedule}</Chip>
        </div>
      )}

      {!partial && !planned && agent.note && <NoteLine note={agent.note} />}
    </MotionCard>
  );
}

function StatusPill({ status }: { status: AgentStatus["status"] }) {
  const map = {
    ACTIVE: { label: "Active", cls: "bg-gains/10 text-gains", dot: "bg-gains" },
    IDLE: { label: "Idle", cls: "bg-border/40 text-text-secondary", dot: "bg-text-secondary/60" },
    PARTIAL: { label: "Subsystem", cls: "bg-warning/10 text-warning", dot: "bg-warning" },
    PLANNED: { label: "Planned", cls: "bg-border/40 text-text-secondary", dot: "bg-text-secondary/40" },
  } as const;
  const s = map[status];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
        s.cls,
      )}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full", s.dot)} />
      {s.label}
    </span>
  );
}

function Chip({ children }: { children: React.ReactNode }) {
  return (
    <span className="rounded-md border border-[var(--hairline)] bg-[var(--hover-wash)] px-2 py-1 font-mono text-[10px] text-text-secondary">
      {children}
    </span>
  );
}

function NoteLine({ note }: { note: string }) {
  const caution = /\bneeds?\b|\bno\b|\bwithout\b/i.test(note);
  return (
    <p
      className={cn(
        "flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px]",
        caution
          ? "border-warning/20 bg-warning/[0.06] text-warning"
          : "border-[var(--hairline)] bg-[var(--hover-wash)] text-text-secondary",
      )}
    >
      <span className={cn("h-1 w-1 shrink-0 rounded-full", caution ? "bg-warning" : "bg-accent")} />
      {note}
    </p>
  );
}

function ClockIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3 w-3 shrink-0" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
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
