"use client";

import { AmbientBackground } from "@/components/shell/AmbientBackground";
import { putInvestorProfile, skipOnboarding } from "@/lib/apiClient";
import { motion, useReducedMotion } from "motion/react";
import { useState } from "react";

const RISK_OPTIONS = [
  { value: "CONSERVATIVE", label: "Conservative" },
  { value: "BALANCED", label: "Balanced" },
  { value: "GROWTH", label: "Growth" },
  { value: "AGGRESSIVE", label: "Aggressive" },
] as const;

const HORIZON_OPTIONS = [
  { value: "LONG_TERM_HOLDER", label: "Long-term holder" },
  { value: "ACTIVE_TRADER", label: "Active trader" },
  { value: "MIX", label: "A mix of both" },
] as const;

const GOAL_SUGGESTIONS = ["Growing wealth over time", "Saving for something specific", "Just learning / following along"];

/**
 * First-login questions (Phase 2, multi-user): three short, skippable questions that seed this
 * person's own investor profile — risk tolerance and trading horizon aren't just chat flavor, they
 * lean how Argus frames its own calls (CORE_HOLD vs. shorter WATCH-style picks) for this person from
 * day one. Shown exactly once (see `InvestorProfileService.needsOnboarding`); editable later any time
 * from Profile → Investor profile.
 */
export function OnboardingQuestions({ onDone }: { onDone: () => void }) {
  const reduce = useReducedMotion();
  const [risk, setRisk] = useState<string | null>(null);
  const [horizon, setHorizon] = useState<string | null>(null);
  const [goal, setGoal] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rise = (delay: number) =>
    reduce ? {} : { initial: { opacity: 0, y: 14 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.5, delay, ease: [0.16, 1, 0.3, 1] as const } };

  async function finish() {
    setSaving(true);
    setError(null);
    try {
      await putInvestorProfile({
        riskTolerance: risk,
        tradingHorizon: horizon,
        financialGoal: goal.trim() || null,
        targetAmount: null,
        targetDate: null,
        residency: null,
        homeCurrency: null,
        notes: null,
      });
      onDone();
    } catch {
      setError("Couldn't save that — try again, or skip for now.");
      setSaving(false);
    }
  }

  async function skip() {
    setSaving(true);
    try {
      await skipOnboarding();
    } catch {
      // Even if the skip call fails, don't trap the person behind this screen.
    } finally {
      onDone();
    }
  }

  return (
    <main className="editorial-theme relative flex min-h-dvh items-center justify-center overflow-hidden bg-background px-6 py-12">
      <AmbientBackground />
      <div className="relative z-10 w-full max-w-md">
        <motion.p {...rise(0)} className="text-center text-[11px] font-medium uppercase tracking-[0.3em] text-text-secondary">
          Before you start
        </motion.p>
        <motion.h1 {...rise(0.08)} className="font-serif-editorial mt-3 text-center text-3xl font-normal tracking-tight text-text-primary">
          A few quick questions
        </motion.h1>
        <motion.p {...rise(0.16)} className="mx-auto mt-3 max-w-xs text-center text-sm leading-relaxed text-text-secondary">
          Helps Argus frame its calls for you specifically. Skippable, and editable later in Profile.
        </motion.p>

        <motion.div {...rise(0.26)} className="mt-9">
          <Question label="How would you describe your investing style?">
            <ChipRow options={RISK_OPTIONS} value={risk} onChange={setRisk} />
          </Question>

          <Question label="Long-term holder, or more of an active trader?">
            <ChipRow options={HORIZON_OPTIONS} value={horizon} onChange={setHorizon} />
          </Question>

          <Question label="What are you mainly here for?">
            <input
              value={goal}
              onChange={(e) => setGoal(e.target.value)}
              placeholder={GOAL_SUGGESTIONS[0]}
              maxLength={200}
              className="w-full border border-[var(--hairline)] bg-transparent px-3 py-2.5 text-sm text-text-primary outline-none focus:border-accent"
            />
            <div className="mt-2 flex flex-wrap gap-1.5">
              {GOAL_SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => setGoal(s)}
                  className="rounded-full border border-[var(--hairline)] px-2.5 py-1 text-[11px] text-text-secondary transition-colors hover:border-accent hover:text-accent"
                >
                  {s}
                </button>
              ))}
            </div>
          </Question>
        </motion.div>

        {error && (
          <motion.p initial={reduce ? undefined : { opacity: 0 }} animate={{ opacity: 1 }} className="mt-4 text-center text-sm text-losses" role="alert">
            {error}
          </motion.p>
        )}

        <motion.div {...rise(0.34)} className="mt-8 flex items-center gap-3">
          <button
            type="button"
            onClick={skip}
            disabled={saving}
            className="flex-1 border border-[var(--hairline)] py-3 text-sm font-medium text-text-secondary transition-colors hover:border-text-secondary disabled:opacity-50"
          >
            Skip for now
          </button>
          <button
            type="button"
            onClick={finish}
            disabled={saving}
            className="flex-1 border border-accent bg-accent py-3 text-sm font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {saving ? "Saving…" : "Continue"}
          </button>
        </motion.div>
      </div>
    </main>
  );
}

function Question({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-6">
      <p className="mb-2.5 text-sm font-medium text-text-primary">{label}</p>
      {children}
    </div>
  );
}

function ChipRow<T extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly { value: T; label: string }[];
  value: T | null;
  onChange: (v: T) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {options.map((o) => {
        const selected = value === o.value;
        return (
          <button
            key={o.value}
            type="button"
            onClick={() => onChange(o.value)}
            aria-pressed={selected}
            className={`rounded-full border px-3.5 py-1.5 text-xs font-medium transition-colors ${
              selected
                ? "border-accent bg-accent/[0.1] text-accent"
                : "border-[var(--hairline)] text-text-secondary hover:border-accent/60 hover:text-accent"
            }`}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}
