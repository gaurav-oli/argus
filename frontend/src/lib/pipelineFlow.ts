/**
 * Agent pipeline — how each agent's wire should look, from real status (Agents page, Terminal Noir).
 *
 * The old pipeline animated every agent identically. Here the particles carry information:
 *   - busy:       reported within its own cadence → a dense, fast stream (denser for frequent agents)
 *   - between:    healthy, but between runs → a single slow, faint particle
 *   - stalled:    silent past its stale threshold (shared with the freshness alert) → the wire breaks
 *   - continuous: always-on (Agent 6) → a steady stream
 *   - oncall:     runs only when asked (Agent 9) → an empty wire
 *   - nodata:     a scheduled agent with nothing captured yet → an empty wire, not an alarm
 *   - planned:    not built → dimmed
 */

/** The three streams the fleet actually flows in, merging into the core. */
export type Stream = "sources" | "market" | "analysis";

export const STREAMS: { id: Stream; label: string }[] = [
  { id: "sources", label: "Sources" },
  { id: "market", label: "Market" },
  { id: "analysis", label: "Analysis & decision" },
];

const STREAM_OF: Record<string, Stream> = {
  news: "sources",
  macro: "sources",
  social: "sources",
  internet: "sources",
  filings: "sources",
  calendar: "sources",
  technical: "market",
  fundamentals: "market",
  "filings-reader": "market",
  strategies: "market",
  deep: "analysis",
  research: "analysis",
  learner: "analysis",
  recommender: "analysis",
  cost: "analysis",
};

/** Unknown (newly added) agents land in Sources rather than disappearing. */
export function streamOf(agentId: string): Stream {
  return STREAM_OF[agentId] ?? "sources";
}

export type WireState = "busy" | "between" | "stalled" | "continuous" | "oncall" | "nodata" | "planned";

export interface WireInput {
  status: string;
  lastActivity: string | null;
  intervalMinutes: number | null;
  staleAfterMinutes: number | null;
  /** The status API's human cadence; "continuous" marks the always-on Agent 6. */
  schedule: string;
}

export interface Wire {
  state: WireState;
  /** Minutes since the last activity, or null when there is none. */
  ageMinutes: number | null;
  /** Particles on the wire. */
  particles: number;
  /** Seconds for one particle to cross the wire. */
  seconds: number;
}

export function classifyWire(a: WireInput, now: number): Wire {
  const ageMinutes = a.lastActivity == null ? null : Math.max(0, (now - new Date(a.lastActivity).getTime()) / 60_000);
  if (a.status === "PLANNED") return { state: "planned", ageMinutes, particles: 0, seconds: 0 };
  if (a.intervalMinutes == null) {
    // No fixed cadence: Agent 6 is always on; Agent 9 only runs when asked.
    return a.schedule === "continuous"
      ? { state: "continuous", ageMinutes, particles: 2, seconds: 2 }
      : { state: "oncall", ageMinutes, particles: 0, seconds: 0 };
  }
  if (ageMinutes == null) return { state: "nodata", ageMinutes, particles: 0, seconds: 0 };
  if (a.staleAfterMinutes != null && ageMinutes > a.staleAfterMinutes) {
    return { state: "stalled", ageMinutes, particles: 0, seconds: 0 };
  }
  if (ageMinutes <= a.intervalMinutes) {
    const particles = a.intervalMinutes <= 10 ? 4 : a.intervalMinutes <= 60 ? 3 : 2;
    return { state: "busy", ageMinutes, particles, seconds: a.intervalMinutes <= 10 ? 1.2 : 2.2 };
  }
  return { state: "between", ageMinutes, particles: 1, seconds: 4.5 };
}

/** "2m", "3h", "4d" — compact elapsed time for the wire's readout. */
export function compactAge(minutes: number): string {
  if (minutes < 1) return "now";
  if (minutes < 60) return `${Math.round(minutes)}m`;
  if (minutes < 1440) return `${Math.round(minutes / 60)}h`;
  return `${Math.round(minutes / 1440)}d`;
}

/** The short readout at the end of an agent's wire. */
export function wireLabel(w: Wire): string {
  switch (w.state) {
    case "busy":
    case "between":
      return w.ageMinutes == null ? "" : `${compactAge(w.ageMinutes)} ago`;
    case "stalled":
      return w.ageMinutes == null ? "stalled" : `stalled ${compactAge(w.ageMinutes)}`;
    case "continuous":
      return "always on";
    case "oncall":
      return "on call";
    case "nodata":
      return "no data yet";
    case "planned":
      return "planned";
  }
}

/**
 * The curved connector from a stream's trunk (left edge, at that stream's vertical middle) into the
 * core (right edge, at the core's vertical middle) — so all three streams visibly converge on the
 * core, not just whichever one happens to sit level with it.
 */
export function convergePath(fromY: number, toY: number, width: number): string {
  const mid = width / 2;
  return `M0 ${fromY.toFixed(1)} C${mid} ${fromY.toFixed(1)} ${mid} ${toY.toFixed(1)} ${width} ${toY.toFixed(1)}`;
}

/** Particle / accent colour per stream — shared by the pipeline and the dossiers below it. */
export const STREAM_TONE: Record<Stream, string> = {
  sources: "var(--color-accent)",
  market: "var(--color-warning)",
  analysis: "var(--color-gains)",
};

/** Who each agent's output goes to (the dossier's "reports to" line). */
const REPORTS_TO: Record<string, string[]> = {
  news: ["Macro", "Recommender", "Deep Analyst"],
  macro: ["Recommender"],
  social: ["Recommender"],
  internet: ["Recommender"],
  filings: ["Recommender", "Filings Reader"],
  calendar: ["Recommender"],
  technical: ["Deep Analyst", "Recommender"],
  fundamentals: ["Deep Analyst"],
  "filings-reader": ["Deep Analyst"],
  strategies: ["Deep Analyst"],
  deep: ["Recommender"],
  research: ["You"],
  learner: ["Recommender", "Deep Analyst"],
  recommender: ["You"],
  cost: ["Every model call"],
};

export function reportsTo(agentId: string): string[] {
  return REPORTS_TO[agentId] ?? [];
}

/** The rubber stamp on a dossier's front. */
export type Stamp = "ACTIVE" | "ON WATCH" | "ON CALL" | "OFF GRID" | "NO DATA" | "PLANNED";

export function stampFor(w: Wire): Stamp {
  switch (w.state) {
    case "busy":
    case "between":
      return "ACTIVE";
    case "continuous":
      return "ON WATCH";
    case "oncall":
      return "ON CALL";
    case "stalled":
      return "OFF GRID";
    case "nodata":
      return "NO DATA";
    case "planned":
      return "PLANNED";
  }
}

export interface DossierStats {
  /** Output so far, log-scaled so 40 and 40,000 are both readable: 0–1. */
  volume: number;
  /** How often it runs, log-scaled from daily-ish to every 5 min: 0–1. */
  tempo: number;
  /** How much of its allowed quiet time is left before it would stall: 0–1. */
  fresh: number;
  /** Bars shed rising "bit dust" (a working agent). */
  live: boolean;
  /** The freshness bar's bits crumble downward instead (a stalled agent). */
  decaying: boolean;
}

/** Above this many captures the volume bar is simply full. */
const VOLUME_CEILING = 50_000;
/** Runs per day that fill the tempo bar (every 5 minutes). */
const TEMPO_CEILING = 288;

export function dossierStats(a: WireInput & { captured: number }, w: Wire): DossierStats {
  const clamp = (x: number) => Math.max(0, Math.min(1, x));
  const volume = clamp(Math.log10(1 + Math.max(0, a.captured)) / Math.log10(1 + VOLUME_CEILING));
  const tempo =
    w.state === "continuous"
      ? 1
      : a.intervalMinutes == null || a.intervalMinutes <= 0
        ? 0
        : clamp(Math.log10(1 + 1440 / a.intervalMinutes) / Math.log10(1 + TEMPO_CEILING));
  let fresh: number;
  if (w.state === "stalled") fresh = 0.08; // a sliver, so the red decay has something to crumble from
  else if (w.state === "continuous") fresh = 1;
  else if (a.staleAfterMinutes == null || w.ageMinutes == null) fresh = 0;
  else fresh = clamp(1 - w.ageMinutes / a.staleAfterMinutes);
  const live = w.state === "busy" || w.state === "between" || w.state === "continuous";
  return { volume, tempo, fresh, live, decaying: w.state === "stalled" };
}
