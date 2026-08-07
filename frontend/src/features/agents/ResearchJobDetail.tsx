"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

import { getResearchJob, type ResearchJobView, type ResearchStep } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { cn } from "@/lib/utils";

const POLL_MS = 2000;

/** Renders the report with this app's own typography tokens rather than a generic prose plugin —
 * matches the hand-styled convention `SummaryBlock`/`ChatPanel` already use for LLM-written text. */
const markdownComponents: Components = {
  h1: ({ children }) => <h1 className="font-display text-lg font-bold text-text-primary">{children}</h1>,
  h2: ({ children }) => (
    <h2 className="mt-4 font-display text-base font-semibold text-text-primary first:mt-0">{children}</h2>
  ),
  h3: ({ children }) => <h3 className="mt-3 text-sm font-semibold text-text-primary">{children}</h3>,
  p: ({ children }) => <p className="mt-2 text-sm leading-relaxed text-text-secondary first:mt-0">{children}</p>,
  ul: ({ children }) => (
    <ul className="mt-2 flex list-disc flex-col gap-1 pl-4 text-sm text-text-secondary">{children}</ul>
  ),
  ol: ({ children }) => (
    <ol className="mt-2 flex list-decimal flex-col gap-1 pl-4 text-sm text-text-secondary">{children}</ol>
  ),
  li: ({ children }) => <li className="pl-1">{children}</li>,
  strong: ({ children }) => <strong className="font-semibold text-text-primary">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  hr: () => <hr className="my-3 border-border/60" />,
  blockquote: ({ children }) => (
    <blockquote className="mt-2 border-l-2 border-accent/40 pl-3 text-sm italic text-text-secondary">
      {children}
    </blockquote>
  ),
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer" className="text-accent underline-offset-2 hover:underline">
      {children}
    </a>
  ),
  code: ({ children }) => (
    <code className="rounded bg-[var(--hover-wash)] px-1 py-0.5 font-mono text-[12px]">{children}</code>
  ),
  table: ({ children }) => (
    <div className="mt-2 overflow-x-auto">
      <table className="w-full text-left text-xs">{children}</table>
    </div>
  ),
  th: ({ children }) => <th className="border-b border-border py-1 pr-3 font-medium text-text-primary">{children}</th>,
  td: ({ children }) => <td className="border-b border-border/60 py-1 pr-3 text-text-secondary">{children}</td>,
};

/**
 * The live view of one research job: the plan as a step list that animates in and updates as the
 * Investor... no — Agent 9 works through it (each step: pending → running → done), a "revising plan"
 * moment when the agent changes its own remaining steps, and the final markdown report. Subscribes to
 * `/topic/research/{id}` for live pushes, with a poll fallback while non-terminal and a fresh fetch on
 * mount so a page refresh mid-job resumes exactly where it was.
 */
export function ResearchJobDetail({
  initial,
  onUpdate,
}: {
  initial: ResearchJobView;
  onUpdate: (job: ResearchJobView) => void;
}) {
  const [job, setJob] = useState<ResearchJobView>(initial);

  useEffect(() => {
    // Re-hydrate on mount (a WebSocket push is best-effort — this isn't) rather than trusting
    // `initial`, which could already be stale by the time this row gets expanded.
    let active = true;
    getResearchJob(initial.id)
      .then((v) => {
        if (active) {
          setJob(v);
          onUpdate(v);
        }
      })
      .catch(() => {});
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial.id]);

  useEffect(() => {
    const sub = subscribeToTopic<ResearchJobView>(`/topic/research/${initial.id}`, (v) => {
      setJob(v);
      onUpdate(v);
    });
    return () => sub.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initial.id]);

  const inProgress = job.status !== "DONE" && job.status !== "FAILED";
  useEffect(() => {
    if (!inProgress) return;
    const t = setInterval(() => {
      getResearchJob(initial.id)
        .then((v) => {
          setJob(v);
          onUpdate(v);
        })
        .catch(() => {});
    }, POLL_MS);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inProgress, initial.id]);

  return (
    <div className="flex flex-col gap-4">
      {job.plan.length > 0 && <PlanList plan={job.plan} revising={job.status === "REVISING_PLAN"} />}

      {job.status === "FAILED" && job.error && (
        <p className="rounded-lg border border-losses/20 bg-losses/[0.06] px-3 py-2 text-xs text-losses">
          Research failed: {job.error}
        </p>
      )}

      {job.status === "DONE" && job.report && (
        <div className="rounded-lg border border-border bg-surface p-4">
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
            {job.report}
          </ReactMarkdown>
        </div>
      )}
    </div>
  );
}

function PlanList({ plan, revising }: { plan: ResearchStep[]; revising: boolean }) {
  const reduce = useReducedMotion();
  return (
    <div className="flex flex-col gap-2">
      <ul className="flex flex-col gap-2">
        <AnimatePresence initial={false}>
          {plan.map((step, i) => (
            <motion.li
              key={step.id}
              layout
              initial={reduce ? false : { opacity: 0, y: 16, scale: 0.985 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={reduce ? { opacity: 0 } : { opacity: 0, y: -8 }}
              transition={reduce ? { duration: 0 } : { delay: i * 0.07, type: "spring", stiffness: 120, damping: 18 }}
            >
              <StepRow step={step} index={i} />
            </motion.li>
          ))}
        </AnimatePresence>
      </ul>
      {revising && <RevisingIndicator />}
    </div>
  );
}

function StepRow({ step, index }: { step: ResearchStep; index: number }) {
  const reduce = useReducedMotion();
  const running = step.status === "RUNNING";
  const done = step.status === "DONE";
  const failed = step.status === "FAILED" || step.status === "SKIPPED";

  return (
    <div className="flex items-center gap-3 rounded-xl border border-border bg-surface p-3">
      <div
        className={cn(
          "relative flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border",
          running ? "border-accent/30 bg-accent/[0.07]" : done ? "border-gains/30 bg-gains/[0.07]" : "border-border bg-[var(--hover-wash)]",
        )}
      >
        {running && (
          <span className="absolute inset-0 rounded-xl ring-1 ring-accent/40">
            {!reduce && <span className="absolute inset-0 animate-ping rounded-xl bg-accent/10" />}
          </span>
        )}
        {done ? (
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" className="text-gains" aria-hidden>
            <motion.path
              d="M20 6 9 17l-5-5"
              initial={reduce ? false : { pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.35, ease: "easeOut" }}
            />
          </svg>
        ) : (
          <span className={cn("font-mono text-sm font-bold", running ? "text-accent" : "text-text-secondary")}>
            {index + 1}
          </span>
        )}
      </div>

      <div className="min-w-0 flex-1">
        <p className={cn("text-sm font-medium", failed ? "text-text-secondary line-through" : "text-text-primary")}>
          {step.label}
        </p>
        <p className="mt-0.5 truncate text-[11px] text-text-secondary">{step.why}</p>
      </div>

      <span className="shrink-0 rounded-md border border-[var(--hairline)] bg-[var(--hover-wash)] px-1.5 py-0.5 font-mono text-[10px] text-text-secondary">
        {step.dataSource}
      </span>
    </div>
  );
}

/** The moment Agent 9 decides its own plan should change — same 3-dot pulse ChatPanel uses for
 * "thinking", so a plan revision reads as the agent genuinely reconsidering, not just a UI glitch. */
function RevisingIndicator() {
  return (
    <div className="flex items-center gap-2 rounded-lg border border-warning/20 bg-warning/[0.06] px-3 py-2 text-xs text-warning">
      <span className="flex items-center gap-1">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="h-1.5 w-1.5 rounded-full bg-warning"
            animate={{ opacity: [0.3, 1, 0.3] }}
            transition={{ duration: 1.1, repeat: Infinity, delay: i * 0.18 }}
          />
        ))}
      </span>
      Revising the plan based on what it just found…
    </div>
  );
}
