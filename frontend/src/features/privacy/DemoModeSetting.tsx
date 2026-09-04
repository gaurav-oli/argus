"use client";

import { useState } from "react";
import { useDemoMode } from "./DemoModeProvider";

/** Profile → Demo Mode: hide the Portfolio section and mask $ amounts/holdings elsewhere, for
 * showing the product to someone else without exposing real financial data. Toggle on before a
 * demo, forget about it after — it's persisted, not a session-only switch. */
export function DemoModeSetting() {
  const { demoMode, loaded, setDemoMode } = useDemoMode();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function toggle() {
    const next = !demoMode;
    setError(null);
    setSaving(true);
    try {
      await setDemoMode(next);
    } catch {
      setError("Couldn't save — try again");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <button
        type="button"
        role="switch"
        aria-checked={demoMode}
        disabled={!loaded || saving}
        onClick={toggle}
        className="flex items-center justify-between gap-3 text-left disabled:opacity-40"
      >
        <span className="text-sm font-medium text-text-primary">Demo Mode</span>
        <span className={`relative h-5 w-9 shrink-0 rounded-full transition ${demoMode ? "bg-accent" : "bg-border"}`}>
          <span
            className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all ${demoMode ? "left-4" : "left-0.5"}`}
          />
        </span>
      </button>
      <p className="text-xs text-text-secondary">
        Hides Portfolio and masks $ amounts and holdings everywhere else — for showing Argus to someone
        without exposing your real financial data.
      </p>
      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
