"use client";

import { useReducedMotion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import type { AgentStatus } from "@/lib/apiClient";
import {
  STREAMS,
  STREAM_TONE,
  classifyWire,
  dossierStats,
  reportsTo,
  stampFor,
  streamOf,
  wireLabel,
  type Stamp,
  type Stream,
} from "@/lib/pipelineFlow";
import { cn } from "@/lib/utils";

/** Segments per stat bar. */
const CELLS = 14;
/** One segment lights every this many ms while the bars load. */
const FILL_STEP_MS = 70;

const BLURB: Record<Stream, string> = {
  sources: "raw signal: news, crowd, filings, calendar",
  market: "prices, fundamentals, filings text, strategies",
  analysis: "where it comes together into your calls",
};

/** Short, meaningful codenames — scaled to fit the card, so none is ever clipped. */
const CODENAME: Record<string, string> = {
  news: "NEWS",
  macro: "MACRO",
  social: "SOCIAL",
  internet: "WEB",
  filings: "INSIDER",
  calendar: "CALENDAR",
  technical: "CHARTS",
  fundamentals: "FUNDAMENTALS",
  "filings-reader": "FILINGS",
  strategies: "STRATEGIES",
  deep: "DEEP",
  research: "RESEARCH",
  learner: "LEARNER",
  recommender: "RECOMMENDER",
  cost: "COST",
};

const STAMP_COLOR: Record<Stamp, string> = {
  ACTIVE: "var(--color-gains)",
  "ON WATCH": "var(--color-gains)",
  "ON CALL": "var(--color-text-secondary)",
  "OFF GRID": "var(--color-losses)",
  "NO DATA": "var(--color-text-secondary)",
  PLANNED: "var(--color-text-secondary)",
};

/**
 * "What each agent is doing" (Agents page, below the pipeline) as classified dossiers, filed into the
 * same three cabinets as the pipeline: Sources / Market / Analysis, in the same colours.
 *
 * Each file's front shows a codename, a status line (ACTIVE / ON WATCH / ON CALL / OFF GRID / NO
 * DATA, with when it last reported) and three segmented stat bars: VOLUME (output so far), TEMPO (how often it runs) and FRESH
 * (how much of its allowed quiet time is left). The bars load segment by segment; on a working
 * agent they then shed a stardust of rising 0/1 bits, and on a stalled one the freshness bar's bits
 * crumble downward in red. Turning a file over shows the brief: what it does, its shift, its note
 * and who it reports to. Stamps and bars use the pipeline's rules (lib/pipelineFlow), so the two
 * views never disagree. Reduced motion: bars show full, no dust, no flip animation.
 *
 * `back` is extra content for one agent's brief (Agent 8's "Review now"), keyed by agent id.
 */
export function AgentDossiers({
  agents,
  back,
}: {
  agents: AgentStatus[];
  back?: Record<string, React.ReactNode>;
}) {
  const reduce = useReducedMotion();
  const [now, setNow] = useState(() => Date.now());
  const [turned, setTurned] = useState<Record<string, boolean>>({});
  const [fill, setFill] = useState(0);

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  // Bars load segment by segment once, on first show.
  useEffect(() => {
    if (reduce) return;
    const id = setInterval(() => {
      setFill((f) => {
        if (f >= CELLS) {
          clearInterval(id);
          return f;
        }
        return f + 1;
      });
    }, FILL_STEP_MS);
    return () => clearInterval(id);
  }, [reduce]);
  const shownCells = reduce ? CELLS : fill;

  const cabinets = useMemo(
    () =>
      STREAMS.map((s) => ({
        ...s,
        files: agents
          .filter((a) => streamOf(a.id) === s.id)
          .map((a) => {
            const wire = classifyWire(a, now);
            return { agent: a, wire, stamp: stampFor(wire), stats: dossierStats(a, wire) };
          }),
      })).filter((c) => c.files.length > 0),
    [agents, now],
  );

  return (
    <div className="flex flex-col gap-6">
      {cabinets.map((cab, ci) => {
        const working = cab.files.filter((f) => f.stamp === "ACTIVE" || f.stamp === "ON WATCH").length;
        const off = cab.files.filter((f) => f.stamp === "OFF GRID").length;
        const tone = STREAM_TONE[cab.id];
        return (
          <section key={cab.id} aria-label={`${cab.label} agents`}>
            <div
              className="term-line-in mb-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b-2 pb-1.5"
              style={{ borderColor: tone, animationDelay: `${ci * 0.15}s` }}
            >
              <h2 className="font-display text-2xl uppercase leading-none tracking-[0.08em]" style={{ color: tone }}>
                {cab.label}
              </h2>
              <span className="text-xs text-text-secondary">{BLURB[cab.id]}</span>
              <span className="ml-auto font-mono text-[11px] text-text-secondary">
                {cab.files.length} files · <span className="text-gains">{working} active</span>
                {off > 0 && <span className="text-losses"> · {off} off grid</span>}
              </span>
            </div>
            {/* Columns come from the space the cards actually get (the page is width-capped), not from
                the screen size — a wide monitor with a narrow page used to squeeze six cards in. */}
            <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,13.5rem),1fr))] gap-3">
              {cab.files.map((f, i) => (
                <Dossier
                  key={f.agent.id}
                  file={f}
                  tone={tone}
                  index={ci * 6 + i}
                  shownCells={shownCells}
                  reduce={!!reduce}
                  turned={!!turned[f.agent.id]}
                  onTurn={() => setTurned((t) => ({ ...t, [f.agent.id]: !t[f.agent.id] }))}
                  back={back?.[f.agent.id]}
                />
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}

type File = {
  agent: AgentStatus;
  wire: ReturnType<typeof classifyWire>;
  stamp: Stamp;
  stats: ReturnType<typeof dossierStats>;
};

function Dossier({
  file,
  tone,
  index,
  shownCells,
  reduce,
  turned,
  onTurn,
  back,
}: {
  file: File;
  tone: string;
  index: number;
  shownCells: number;
  reduce: boolean;
  turned: boolean;
  onTurn: () => void;
  back?: React.ReactNode;
}) {
  const { agent, wire, stamp, stats } = file;
  const num = agent.code.replace(/[^0-9]/g, "").padStart(2, "0");
  const stalled = stamp === "OFF GRID";
  const tempoLabel =
    wire.state === "continuous"
      ? "always on"
      : agent.intervalMinutes == null
        ? "on demand"
        : `every ${formatMinutes(agent.intervalMinutes)}`;
  const freshLabel = wire.ageMinutes == null ? "—" : wireLabel(wire);
  const short = CODENAME[agent.id] ?? codename(agent.name);
  const reports = reportsTo(agent.id);

  return (
    <div className="dz-card h-[268px]" style={{ animationDelay: `${index * 0.04}s` }}>
      <div className={cn("dz-inner", turned && "dz-turned", reduce && "dz-still")}>
        {/* Front — the whole face turns the file over. */}
        <button
          type="button"
          onClick={onTurn}
          aria-pressed={turned}
          aria-label={`${agent.code} ${agent.name}: ${stamp.toLowerCase()}. Turn over for the brief.`}
          tabIndex={turned ? -1 : 0}
          className={cn(
            "dz-face glass flex flex-col p-3 text-left transition-colors [container-type:inline-size] hover:border-accent",
            stalled && "border-losses/70",
          )}
        >
          <span className="flex justify-between font-mono text-[9px] uppercase tracking-[0.12em] text-text-secondary">
            <span>File A-{num}</span>
            <span style={{ color: tone }}>{streamOf(agent.id)}</span>
          </span>
          <span
            className="mt-2 block overflow-hidden whitespace-nowrap font-display leading-none"
            style={{ color: tone, fontSize: `min(2.5rem, calc(100cqw / ${Math.max(4, short.length) * 0.55}))` }}
          >
            {short}
          </span>
          <span className="mt-0.5 truncate text-xs text-text-primary">{agent.name}</span>
          <span className="mt-2 flex items-center gap-2 font-mono text-[10px] tracking-[0.12em]">
            <span
              className="inline-flex items-center gap-1.5 border px-1.5 py-0.5 font-bold"
              style={{ color: STAMP_COLOR[stamp], borderColor: STAMP_COLOR[stamp] }}
            >
              <span className="relative inline-flex h-1.5 w-1.5" aria-hidden>
                {!reduce && (stamp === "ACTIVE" || stamp === "ON WATCH") && (
                  <span className="absolute inset-0 animate-ping" style={{ background: STAMP_COLOR[stamp], opacity: 0.6 }} />
                )}
                <span
                  className={cn("relative inline-flex h-1.5 w-1.5", !reduce && stalled && "animate-pulse")}
                  style={{ background: STAMP_COLOR[stamp] }}
                />
              </span>
              {stamp}
            </span>
            {wire.ageMinutes != null && <span className="truncate tracking-normal text-text-secondary">{freshLabel}</span>}
          </span>
          <span className="mt-3 flex flex-col gap-2">
            <StatBar label="Volume" value={compact(agent.captured)} frac={stats.volume} color="var(--color-text-primary)" live={stats.live} shownCells={shownCells} seed={index * 3} reduce={reduce} />
            <StatBar label="Tempo" value={tempoLabel} frac={stats.tempo} color="var(--color-warning)" live={stats.live} shownCells={shownCells} seed={index * 3 + 1} reduce={reduce} />
            <StatBar
              label="Fresh"
              value={freshLabel}
              frac={stats.fresh}
              color={stalled ? "var(--color-losses)" : "var(--color-gains)"}
              live={stats.live}
              decaying={stats.decaying}
              shownCells={shownCells}
              seed={index * 3 + 2}
              reduce={reduce}
            />
          </span>
          <span className="mt-auto flex justify-between pt-2 font-mono text-[10px] text-text-secondary">
            <span className="truncate">{agent.captureLabel}</span>
            <span aria-hidden>↻ turn over</span>
          </span>
        </button>

        {/* Back — the brief, on cream paper. */}
        <div
          className="dz-face dz-back flex flex-col border border-accent bg-[#efe8d4] p-3 text-[#23221d]"
          aria-hidden={!turned}
        >
          <span className="flex justify-between font-mono text-[9px] uppercase tracking-[0.12em] text-[#5c5a50]">
            <span>
              A-{num} · {short}
            </span>
            <span>Brief</span>
          </span>
          <p className="mt-2 overflow-hidden text-[11px] leading-snug">{agent.description}</p>
          <p className="mt-2 font-mono text-[10px] text-[#5c5a50]">Shift · {agent.schedule}</p>
          {agent.note && <p className="mt-1.5 bg-[#23221d] px-1.5 py-1 font-mono text-[10px] text-[#efe8d4]">{agent.note}</p>}
          {back}
          <span className="mt-auto flex items-end justify-between gap-2 pt-2 font-mono text-[10px] text-[#5c5a50]">
            <span className="min-w-0">{reports.length > 0 && <>Reports to → {reports.join(", ")}</>}</span>
            <button
              type="button"
              onClick={onTurn}
              tabIndex={turned ? 0 : -1}
              className="shrink-0 border border-[#23221d] px-1.5 py-0.5 hover:bg-[#23221d] hover:text-[#efe8d4]"
            >
              ↻ back
            </button>
          </span>
        </div>
      </div>
    </div>
  );
}

/** One segmented stat bar; on a working agent it sheds rising 0/1 "bit dust", on a stalled one it decays. */
function StatBar({
  label,
  value,
  frac,
  color,
  live,
  decaying = false,
  shownCells,
  seed,
  reduce,
}: {
  label: string;
  value: string;
  frac: number;
  color: string;
  live: boolean;
  decaying?: boolean;
  shownCells: number;
  seed: number;
  reduce: boolean;
}) {
  const target = Math.max(0, Math.min(CELLS, Math.round(frac * CELLS)));
  const lit = Math.min(target, shownCells);
  const loaded = lit === target && target > 0;
  const pct = (target / CELLS) * 100;
  const dust = !reduce && loaded && (live || decaying);
  const bits = dust ? bitDust(seed, pct, decaying) : [];

  return (
    <span className="block" aria-label={`${label}: ${value}`}>
      <span className="flex justify-between font-mono text-[9px] uppercase tracking-[0.12em] text-text-secondary">
        <span>{label}</span>
        <span className="normal-case tracking-normal">{value}</span>
      </span>
      <span className="relative mt-1 block" aria-hidden>
        <span className="relative grid h-[9px] grid-cols-[repeat(14,minmax(0,1fr))] gap-[2px] overflow-hidden">
          {Array.from({ length: CELLS }, (_, j) => (
            <span
              key={j}
              className={j < lit && !reduce ? "dz-cell-on" : undefined}
              style={{
                background: j < lit ? color : "color-mix(in srgb, var(--color-text-primary) 8%, transparent)",
                boxShadow: j < lit && live ? `0 0 6px ${color}` : undefined,
              }}
            />
          ))}
          {dust && live && (
            <span className="pointer-events-none absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${pct}%` }}>
              <span className="dz-shimmer absolute inset-y-0 w-[30%]" style={{ animationDelay: `${(seed * 0.13) % 2.2}s` }} />
            </span>
          )}
        </span>
        {bits.map((b, k) => (
          <span
            key={k}
            className={cn("dz-bit", decaying ? "dz-bit-fall" : "dz-bit-rise")}
            style={
              {
                left: `${b.left}%`,
                color: decaying ? "var(--color-losses)" : color,
                "--dx": `${b.dx}px`,
                animationDuration: `${b.dur}s`,
                animationDelay: `${b.delay}s`,
              } as React.CSSProperties
            }
          >
            {b.ch}
          </span>
        ))}
      </span>
    </span>
  );
}

/** Deterministic scatter for the bit dust — looks random, never reshuffles on re-render. */
function bitDust(seed: number, edgePct: number, decaying: boolean) {
  const rnd = (n: number) => {
    const x = Math.sin(n * 12.9898) * 43758.5453;
    return x - Math.floor(x);
  };
  const count = decaying ? 4 : 7;
  const spread = decaying ? 60 : 12;
  return Array.from({ length: count }, (_, b) => {
    const s = seed * 97 + b * 7;
    return {
      ch: rnd(s) > 0.5 ? "1" : "0",
      left: Math.max(0, Math.min(98, edgePct - rnd(s + 1) * spread)).toFixed(1),
      dx: Math.round((rnd(s + 2) - 0.5) * 22),
      dur: (decaying ? 2.6 : 1.4 + rnd(s + 3) * 1.1).toFixed(2),
      delay: (-rnd(s + 4) * 2.5).toFixed(2),
    };
  });
}

/** "News Intelligence" → "NEWS", "Filings Reader" → "FILINGS" — the file's codename. */
function codename(name: string): string {
  const first = name.split(/[\s/]+/)[0] ?? name;
  return first.replace(/[^A-Za-z0-9-]/g, "").toUpperCase().slice(0, 8);
}

function formatMinutes(m: number): string {
  if (m < 60) return `${m}m`;
  if (m < 1440) return `${Math.round(m / 60)}h`;
  return `${Math.round(m / 1440)}d`;
}

function compact(n: number): string {
  if (n >= 10_000) return `${(n / 1000).toFixed(1)}k`;
  return n.toLocaleString("en-CA");
}
