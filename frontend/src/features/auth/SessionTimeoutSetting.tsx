"use client";

import { getSessionTimeout, setSessionTimeout } from "@/lib/apiClient";
import { useEffect, useState } from "react";

/** The FR-35 options. `null` = Never. */
const OPTIONS: { label: string; seconds: number | null }[] = [
  { label: "1 minute", seconds: 60 },
  { label: "5 minutes", seconds: 300 },
  { label: "15 minutes", seconds: 900 },
  { label: "30 minutes", seconds: 1800 },
  { label: "1 hour", seconds: 3600 },
  { label: "4 hours", seconds: 14400 },
  { label: "Never", seconds: null },
];

const toKey = (seconds: number | null) => (seconds === null ? "never" : String(seconds));

/** Profile → Security: choose the auto-lock idle timeout (Story 2.3). */
export function SessionTimeoutSetting() {
  const [current, setCurrent] = useState<number | null>(900);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getSessionTimeout()
      .then((t) => {
        if (!active) return;
        setCurrent(t.seconds ?? null);
        setLoaded(true);
      })
      .catch(() => {
        if (active) setLoaded(true);
      });
    return () => {
      active = false;
    };
  }, []);

  async function handleChange(value: string) {
    const seconds = value === "never" ? null : Number(value);
    const previous = current;
    setCurrent(seconds);
    setError(null);
    setSaving(true);
    try {
      await setSessionTimeout(seconds);
    } catch {
      setCurrent(previous); // revert on failure
      setError("Couldn't save — try again");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor="session-timeout" className="text-sm font-medium text-text-primary">
        Auto-lock after
      </label>
      <select
        id="session-timeout"
        value={toKey(current)}
        disabled={!loaded || saving}
        onChange={(e) => handleChange(e.target.value)}
        className="self-start rounded-xl border border-border bg-background px-3 py-2 text-sm text-text-primary outline-none focus:border-accent disabled:opacity-40"
      >
        {OPTIONS.map((o) => (
          <option key={toKey(o.seconds)} value={toKey(o.seconds)}>
            {o.label}
          </option>
        ))}
      </select>
      <p className="text-xs text-text-secondary">
        Argus locks after this much inactivity and asks for your PIN or Face ID.
      </p>
      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
