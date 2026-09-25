"use client";

import { ApiError, runDebate, type DebateView } from "@/lib/apiClient";
import { useState } from "react";

/**
 * "Debate this call" — a bull-vs-bear researcher debate (TradingAgents-style Researcher Team,
 * adapted to a single combined-JSON call, see backend RecommendationDebateService javadoc). Mirrors
 * the "Ask AI" trigger's UX (explicit user action, no auto-load like PersonaTakes) since this
 * escalates to Claude Haiku and shouldn't run silently in the background.
 */
export function RecommendationDebate({ recommendationId }: { recommendationId: number }) {
  const [debate, setDebate] = useState<DebateView | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setPending(true);
    setError(null);
    try {
      setDebate(await runDebate(recommendationId));
    } catch (err) {
      setError(err instanceof ApiError ? (err.problem.detail ?? "Debate failed.") : "Couldn't reach the model.");
    } finally {
      setPending(false);
    }
  }

  if (!debate && !pending && !error) {
    return (
      <button
        onClick={run}
        className="self-start rounded border border-accent/40 px-3 py-1 text-[11px] font-medium text-accent transition-colors hover:bg-accent/10"
      >
        Debate this call
      </button>
    );
  }

  return (
    <div className="flex flex-col gap-2 rounded-lg border border-border bg-background/50 p-3 text-xs">
      {pending && <p className="text-text-secondary">Running the debate — this can take a moment…</p>}
      {error && (
        <>
          <p className="text-losses">{error}</p>
          <button onClick={run} className="self-start text-[11px] font-medium text-accent underline">
            Retry
          </button>
        </>
      )}
      {debate && (
        <>
          <p>
            <span className="font-medium text-gains">Bull case:</span> {debate.bullCase}
          </p>
          <p>
            <span className="font-medium text-losses">Bear case:</span> {debate.bearCase}
          </p>
          <p>
            <span className="font-medium text-text-primary">Synthesis ({debate.verdict}):</span> {debate.synthesis}
          </p>
          <button
            onClick={run}
            disabled={pending}
            className="self-start text-[11px] font-medium text-accent underline disabled:opacity-50"
          >
            Re-run
          </button>
        </>
      )}
    </div>
  );
}
