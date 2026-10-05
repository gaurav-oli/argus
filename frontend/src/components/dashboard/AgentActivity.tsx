"use client";

import { useReducedMotion } from "motion/react";
import { useEffect, useMemo, useRef, useState } from "react";
import { getRecommendations, type RecommendationCard } from "@/lib/apiClient";
import {
  STREAMS,
  STREAM_TONE,
  classifyWire,
  convergePath,
  streamOf,
  wireLabel,
  type Wire,
} from "@/lib/pipelineFlow";
import { cn } from "@/lib/utils";

/** A single agent rendered in the pipeline. */
export type PipelineAgent = {
  id: string;
  name: string;
  code: string;
  status: string;
  lastActivity: string | null;
  intervalMinutes: number | null;
  staleAfterMinutes: number | null;
  schedule: string;
};

/** Particle colour per stream, so you can see which kind of signal is landing in the core. */
const TONE = STREAM_TONE;

const ACTION_TONE: Record<string, string> = {
  STRONG_BUY: "text-gains",
  BUY: "text-gains",
  AVOID: "text-losses",
  STRONG_AVOID: "text-losses",
};

/** Width of the curved connectors between the streams and the core. */
const CONVERGE_W = 64;
/** How often the core cycles to its next call. */
const EMIT_MS = 4000;

/**
 * Live view of the agent fleet (Epic 9, Story 9.1), driven by real per-agent status from
 * {@link AgentFleet}. Same motion language as before (agent boxes, wires, particles, the Argus core),
 * but the motion now carries information:
 *
 * 1. **Particles by recency** — each wire's density and speed come from how recently and how often
 *    that agent reports; a stalled agent's wire breaks (thresholds shared with the freshness alert).
 * 2. **Merging streams + output** — agents are grouped into Sources / Market / Analysis; each
 *    stream's trunk curves into the core, and the core feeds your live calls.
 * 3. **Reactive core** — particles are coloured by stream, the core ripples as signal lands, and it
 *    cycles through the calls it produced, highlighted in the calls box.
 *
 * Reduced motion: no particles or ripples; every state still reads from colour, the broken wire and
 * the text readouts.
 */
export function AgentActivity({ agents }: { agents: PipelineAgent[] }) {
  const reduce = useReducedMotion();
  const [now, setNow] = useState(() => Date.now());
  const [sel, setSel] = useState<string | null>(null);
  const [calls, setCalls] = useState<RecommendationCard[] | null>(null);
  const [emitIx, setEmitIx] = useState(0);

  // Re-derive recency every 30s so "2m ago" and stalled states stay true without a refetch.
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    let active = true;
    const load = () =>
      getRecommendations()
        .then((r) => active && setCalls(r.filter((c) => c.action != null && c.action !== "WATCH")))
        .catch(() => active && setCalls((prev) => prev ?? []));
    load();
    const id = setInterval(load, 60_000);
    return () => {
      active = false;
      clearInterval(id);
    };
  }, []);

  const shownCalls = (calls ?? []).slice(0, 3);
  useEffect(() => {
    if (reduce || shownCalls.length < 2) return;
    const id = setInterval(() => setEmitIx((i) => i + 1), EMIT_MS);
    return () => clearInterval(id);
  }, [reduce, shownCalls.length]);
  const lit = shownCalls.length ? emitIx % shownCalls.length : -1;

  const groups = useMemo(
    () =>
      STREAMS.map((s) => ({
        ...s,
        agents: agents
          .filter((a) => streamOf(a.id) === s.id)
          .map((a) => ({ agent: a, wire: classifyWire(a, now) })),
      })).filter((g) => g.agents.length > 0),
    [agents, now],
  );

  const all = groups.flatMap((g) => g.agents);
  const flowing = all.filter((x) => x.wire.state === "busy" || x.wire.state === "continuous").length;
  const stalled = all.filter((x) => x.wire.state === "stalled").length;

  // Measure where each stream and the core sit, so the connectors converge on the core exactly.
  const wrapRef = useRef<HTMLDivElement>(null);
  const groupRefs = useRef<(HTMLDivElement | null)[]>([]);
  const coreRef = useRef<HTMLDivElement>(null);
  const [geo, setGeo] = useState<{ h: number; ys: number[]; coreY: number } | null>(null);
  useEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    const ro = new ResizeObserver(() => {
      const top = wrap.getBoundingClientRect().top;
      const mid = (el: Element | null) => {
        if (!el) return 0;
        const r = el.getBoundingClientRect();
        return r.top - top + r.height / 2;
      };
      setGeo({ h: wrap.offsetHeight, ys: groupRefs.current.map(mid), coreY: mid(coreRef.current) });
    });
    ro.observe(wrap);
    return () => ro.disconnect();
  }, [groups.length]);

  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Agent Pipeline</h3>
        <span className="flex items-center gap-2 text-[11px] text-text-secondary">
          <span className="relative flex h-2 w-2" aria-hidden>
            {!reduce && <span className="absolute inline-flex h-full w-full animate-ping bg-gains opacity-75" />}
            <span className="relative inline-flex h-2 w-2 bg-gains" />
          </span>
          {flowing} flowing
          {stalled > 0 && <span className="text-losses">· {stalled} stalled</span>}
        </span>
      </div>

      <div ref={wrapRef} className="relative mt-4 flex flex-col gap-6 md:flex-row md:items-stretch md:gap-0">
        {/* Streams */}
        <div className="flex min-w-0 flex-1 flex-col gap-5">
          {groups.map((g, gi) => (
            <div
              key={g.id}
              ref={(el) => {
                groupRefs.current[gi] = el;
              }}
              className="md:border-r-2 md:border-[var(--glass-border)] md:pr-3"
            >
              <p className="mb-1 text-[10px] uppercase tracking-[0.2em]" style={{ color: TONE[g.id] }}>
                {g.label}
              </p>
              <ul className="space-y-1">
                {g.agents.map(({ agent, wire }, i) => (
                  <AgentRow
                    key={agent.id}
                    agent={agent}
                    wire={wire}
                    tone={TONE[g.id]}
                    index={gi * 6 + i}
                    reduce={!!reduce}
                    dimmed={sel != null && sel !== agent.id}
                    selected={sel === agent.id}
                    onSelect={() => setSel((s) => (s === agent.id ? null : agent.id))}
                  />
                ))}
              </ul>
            </div>
          ))}
        </div>

        {/* Curved connectors: every stream's trunk converges on the core (desktop). */}
        <div className="relative hidden shrink-0 md:block" style={{ width: CONVERGE_W }} aria-hidden>
          {geo && (
            <svg width={CONVERGE_W} height={geo.h} className="absolute inset-0 overflow-visible">
              {groups.map((g, gi) => {
                const d = convergePath(geo.ys[gi] ?? 0, geo.coreY, CONVERGE_W);
                return (
                  <g key={g.id}>
                    <path d={d} fill="none" stroke={TONE[g.id]} strokeOpacity={0.5} strokeWidth={2.5} />
                    {!reduce &&
                      [0, 1, 2].map((k) => (
                        <circle key={k} r={3.5} fill={TONE[g.id]} style={{ filter: `drop-shadow(0 0 4px ${TONE[g.id]})` }}>
                          <animateMotion dur="1.4s" begin={`${(k * 0.47 + gi * 0.2).toFixed(2)}s`} repeatCount="indefinite" path={d} />
                        </circle>
                      ))}
                  </g>
                );
              })}
            </svg>
          )}
        </div>

        {/* Core → your calls */}
        <div className="flex shrink-0 items-center gap-0 md:w-[22rem]">
          <div ref={coreRef} className="relative grid h-28 w-28 shrink-0 place-items-center">
            {!reduce && (
              <>
                <span className="pv-ripple absolute inset-3 rounded-full border-2 border-accent" aria-hidden />
                <span className="pv-ripple absolute inset-3 rounded-full border-2 border-accent [animation-delay:.7s]" aria-hidden />
              </>
            )}
            <span className="absolute inset-2 border border-accent/30 bg-accent/[0.06]" aria-hidden />
            <span className="absolute inset-5 border border-accent/50 bg-accent/[0.05]" aria-hidden />
            <span className="relative flex flex-col items-center">
              <span className="h-3 w-3 bg-accent" style={{ boxShadow: "0 0 16px var(--chart-accent)" }} aria-hidden />
              <span className="mt-1.5 text-[11px] font-bold uppercase tracking-[0.14em] text-accent">Argus</span>
              <span className="text-[9px] text-text-secondary">core</span>
            </span>
          </div>

          <div className="relative h-[3px] min-w-6 flex-1 bg-accent/45" aria-hidden>
            {!reduce &&
              shownCalls.length > 0 &&
              [0, 1].map((k) => (
                <span
                  key={k}
                  className="pv-flow absolute -top-[3px] h-[9px] w-[9px] bg-accent"
                  style={{ boxShadow: "0 0 10px var(--chart-accent)", animationDuration: "1.6s", animationDelay: `${-0.8 * k}s` }}
                />
              ))}
          </div>

          <div className="w-44 shrink-0 border border-accent bg-accent/[0.06] p-2.5">
            <p className="text-[10px] uppercase tracking-[0.14em] text-text-secondary">Your calls</p>
            <p className="font-display text-4xl leading-none text-accent">{calls == null ? "—" : calls.length}</p>
            <ul className="mt-1 text-[11px] leading-relaxed">
              {calls != null && shownCalls.length === 0 && <li className="text-text-secondary">nothing actionable</li>}
              {shownCalls.map((c, i) => (
                <li
                  key={c.id}
                  className={cn("px-1 transition-colors duration-300", i === lit && "bg-accent/20")}
                >
                  <span className={ACTION_TONE[c.action ?? ""] ?? "text-accent"}>{c.actionLabel ?? c.action}</span>{" "}
                  {c.ticker} {Math.round((c.direction === "BEARISH" ? c.bearProbability : c.bullProbability) * 100)}%
                </li>
              ))}
            </ul>
          </div>
        </div>
      </div>

      <p className="mt-3 text-[10px] leading-relaxed text-text-secondary">
        fast &amp; dense = reported recently · single slow particle = between runs · red break = stalled ·{" "}
        <span style={{ color: TONE.sources }}>sources</span> / <span style={{ color: TONE.market }}>market</span> /{" "}
        <span style={{ color: TONE.analysis }}>analysis</span> · tap an agent to focus it
      </p>
    </div>
  );
}

function AgentRow({
  agent,
  wire,
  tone,
  index,
  reduce,
  dimmed,
  selected,
  onSelect,
}: {
  agent: PipelineAgent;
  wire: Wire;
  tone: string;
  index: number;
  reduce: boolean;
  dimmed: boolean;
  selected: boolean;
  onSelect: () => void;
}) {
  const stalled = wire.state === "stalled";
  const quiet = wire.state === "between";
  const empty = wire.particles === 0;
  const num = agent.code.replace(/[^0-9]/g, "") || agent.name.slice(0, 2).toUpperCase();
  const color = stalled ? "var(--color-losses)" : tone;

  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-pressed={selected}
        aria-label={`${agent.code} ${agent.name}: ${wireLabel(wire)}`}
        className={cn(
          "flex w-full items-center gap-3 px-1 py-0.5 text-left transition-opacity duration-200 hover:bg-[var(--hover-wash)]",
          dimmed && "opacity-30",
          wire.state === "planned" && "opacity-40",
        )}
      >
        <span
          className="relative flex h-8 w-9 shrink-0 items-center justify-center border bg-[var(--hover-wash)]"
          style={{ borderColor: stalled ? "var(--color-losses)" : selected ? "var(--color-accent)" : "var(--glass-border)" }}
        >
          {!reduce && wire.state === "busy" && (
            <span className="absolute inset-0 animate-ping border" style={{ borderColor: tone, opacity: 0.35 }} aria-hidden />
          )}
          <span className="font-mono text-xs font-bold" style={{ color: stalled ? "var(--color-losses)" : "var(--color-accent)" }}>
            {num}
          </span>
        </span>

        <span className="w-32 shrink-0">
          <span className={cn("block truncate text-sm", selected ? "text-accent" : "text-text-primary")}>{agent.name}</span>
          <span className="block font-mono text-[10px] text-text-secondary">{agent.code}</span>
        </span>

        <span
          className={cn("relative h-px flex-1", stalled && "h-0 border-t border-dashed border-losses/70")}
          style={stalled ? undefined : { background: quiet || empty ? "var(--hairline)" : "linear-gradient(90deg, var(--hairline), color-mix(in srgb, var(--color-accent) 35%, transparent))" }}
          aria-hidden
        >
          {!reduce &&
            Array.from({ length: wire.particles }, (_, k) => (
              <span
                key={k}
                className="pv-flow absolute top-1/2 -translate-y-1/2"
                style={{
                  width: quiet ? 4 : 6,
                  height: quiet ? 4 : 6,
                  background: color,
                  opacity: quiet ? 0.55 : 1,
                  boxShadow: `0 0 6px ${color}`,
                  animationDuration: `${wire.seconds}s`,
                  animationDelay: `${(-(wire.seconds / wire.particles) * k - index * 0.2).toFixed(2)}s`,
                }}
              />
            ))}
          {stalled && (
            <span
              className={cn("absolute left-[46%] top-1/2 h-[7px] w-[7px] -translate-y-1/2 bg-losses", !reduce && "pv-stuck")}
              style={{ boxShadow: "0 0 8px var(--color-losses)" }}
            />
          )}
        </span>

        <span
          className={cn(
            "w-24 shrink-0 text-right font-mono text-[11px] tabular-nums",
            stalled ? "text-losses" : wire.state === "busy" ? "text-gains" : "text-text-secondary",
          )}
        >
          {wireLabel(wire)}
        </span>
      </button>
    </li>
  );
}
