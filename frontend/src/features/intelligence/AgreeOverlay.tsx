"use client";

import { useState } from "react";
import { ApiError, decideRecommendation } from "@/lib/apiClient";
import { cn } from "@/lib/utils";

/**
 * S-D1 — an optional human read on an agent call: Agree or Disagree, with an optional note. It is stored as
 * the signed-in person's own decision (TAKEN = agree, DECLINED = disagree; S-C1 keeps it private to them and
 * separate from the paper Investor's), and the Trade Journal later scores it against how the call actually
 * did — the regret overlay. It never blocks or changes the paper loop.
 */
export function AgreeOverlay({
  recommendationId,
  initial,
}: {
  recommendationId: number;
  initial: "TAKEN" | "DECLINED" | null;
}) {
  const [mine, setMine] = useState(initial);
  const [pick, setPick] = useState<"TAKEN" | "DECLINED" | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (!pick || busy) return;
    setBusy(true);
    setError(null);
    try {
      await decideRecommendation(recommendationId, pick, note.trim() || (pick === "TAKEN" ? "Agree" : "Disagree"));
      setMine(pick);
      setPick(null);
      setNote("");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't save that — try again.");
    } finally {
      setBusy(false);
    }
  }

  const btn = (value: "TAKEN" | "DECLINED", label: string) => (
    <button
      type="button"
      onClick={() => setPick(pick === value ? null : value)}
      aria-pressed={pick === value}
      className={cn(
        "border px-2.5 py-1 font-mono text-[11px] uppercase tracking-wider transition-colors",
        pick === value
          ? value === "TAKEN"
            ? "border-gains bg-gains/15 text-gains"
            : "border-losses bg-losses/12 text-losses"
          : "border-[var(--glass-border)] text-text-secondary hover:text-text-primary",
      )}
    >
      {label}
    </button>
  );

  return (
    <div className="mt-4 border-t border-border pt-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="mr-1 text-[9.5px] uppercase tracking-wide text-text-tertiary">Your read · optional</p>
        {btn("TAKEN", "Agree")}
        {btn("DECLINED", "Disagree")}
        {mine && !pick && (
          <span className="text-[11px] text-text-secondary">
            You marked this{" "}
            <span className={mine === "TAKEN" ? "text-gains" : "text-losses"}>{mine === "TAKEN" ? "agree" : "disagree"}</span> —
            the Trade Journal will score it against how the call does.
          </span>
        )}
      </div>
      {pick && (
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <input
            value={note}
            onChange={(e) => setNote(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && save()}
            maxLength={280}
            placeholder={pick === "TAKEN" ? "Why you agree (optional)" : "Why you disagree (optional)"}
            className="min-w-0 flex-1 border border-[var(--hairline)] bg-transparent px-2.5 py-1.5 text-xs text-text-primary outline-none focus:border-accent"
          />
          <button
            type="button"
            onClick={save}
            disabled={busy}
            className="border border-accent px-3 py-1.5 font-mono text-[11px] uppercase tracking-wider text-accent disabled:opacity-50"
          >
            {busy ? "Saving…" : "Save"}
          </button>
        </div>
      )}
      {error && <p className="mt-1.5 text-[11px] text-losses">{error}</p>}
      <p className="mt-1.5 text-[10.5px] text-text-tertiary">
        Only you see this. It doesn&apos;t change the paper Investor, which trades the call on its own either way.
      </p>
    </div>
  );
}
