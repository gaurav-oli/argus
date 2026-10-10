/**
 * Friendly names for the agent ids that sign recommendation signals ("agent-11-deep" → "Deep Analyst").
 * Mirrors LessonComposer.AGENT_NAMES on the backend; an unknown id is tidied rather than dropped.
 */
const AGENT_NAMES: Record<string, string> = {
  "agent-1-news": "News",
  "agent-2-social": "Social",
  "agent-3-internet": "Internet",
  "agent-4-financial": "SEC insider",
  "agent-7-calendar": "Calendar",
  "agent-8-macro": "Macro",
  "agent-10-technical": "Chart Reader",
  "agent-11-cause": "Cause of move",
  "agent-11-deep": "Deep Analyst",
  "agent-12-fundamental": "Fundamentals",
  "agent-14-filings": "Filings Reader",
  "agent-15-academic": "Academic Strategies",
};

export function agentName(id: string | null | undefined): string {
  if (!id) return "an agent";
  const known = AGENT_NAMES[id];
  if (known) return known;
  const tidy = id.replace(/^agent-\d+-/, "").replace(/-/g, " ");
  return tidy ? tidy.charAt(0).toUpperCase() + tidy.slice(1) : id;
}

export interface SignalLike {
  agent: string;
  direction: "BULLISH" | "BEARISH" | "NEUTRAL";
  weight: number;
}

/** The signals that pushed hardest in the call's direction, strongest first. */
export function topSignals<T extends SignalLike>(signals: T[] | null | undefined, direction: "BULLISH" | "BEARISH", n = 3): T[] {
  return (signals ?? [])
    .filter((s) => s.direction === direction && Math.abs(s.weight) > 0)
    .sort((a, b) => Math.abs(b.weight) - Math.abs(a.weight))
    .slice(0, n);
}
