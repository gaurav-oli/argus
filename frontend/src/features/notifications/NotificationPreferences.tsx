"use client";

import { useEffect, useState } from "react";

import { getNotificationPrefs, putNotificationPrefs, type NotificationPrefs } from "@/lib/apiClient";

/**
 * Global push preferences (Profile → Notifications): which categories may push, quiet hours (local
 * time — non-critical pushes held overnight), and per-ticker mutes. Applies to every device; the
 * server enforces them before any Web Push fan-out.
 */
export function NotificationPreferences() {
  const [loaded, setLoaded] = useState(false);
  const [briefing, setBriefing] = useState(true);
  const [breaking, setBreaking] = useState(true);
  const [alerts, setAlerts] = useState(true);
  const [quietOn, setQuietOn] = useState(false);
  const [quietStart, setQuietStart] = useState(22);
  const [quietEnd, setQuietEnd] = useState(7);
  const [muted, setMuted] = useState<string[]>([]);
  const [tickerInput, setTickerInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getNotificationPrefs()
      .then((p) => {
        if (!active) return;
        setBriefing(p.briefingEnabled);
        setBreaking(p.breakingEnabled);
        setAlerts(p.alertsEnabled);
        if (p.quietStartHour != null && p.quietEndHour != null) {
          setQuietOn(true);
          setQuietStart(p.quietStartHour);
          setQuietEnd(p.quietEndHour);
        }
        setMuted(p.mutedTickers);
      })
      .catch(() => active && setError("Couldn't load preferences."))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, []);

  function addTicker() {
    const t = tickerInput.trim().toUpperCase();
    if (t && !muted.includes(t)) setMuted([...muted, t]);
    setTickerInput("");
  }

  async function save() {
    setSaving(true);
    setError(null);
    const prefs: NotificationPrefs = {
      briefingEnabled: briefing,
      breakingEnabled: breaking,
      alertsEnabled: alerts,
      quietStartHour: quietOn ? quietStart : null,
      quietEndHour: quietOn ? quietEnd : null,
      mutedTickers: muted,
    };
    try {
      await putNotificationPrefs(prefs);
      setSavedAt(Date.now());
    } catch {
      setError("Couldn't save preferences.");
    } finally {
      setSaving(false);
    }
  }

  if (!loaded) return <p className="text-xs text-text-secondary">Loading preferences…</p>;

  return (
    <div className="mt-4 flex flex-col gap-4 border-t border-border/50 pt-4">
      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wide text-text-secondary">What can notify you</h4>
        <div className="mt-2 flex flex-col gap-2">
          <Toggle label="Morning briefing" checked={briefing} onChange={setBriefing} />
          <Toggle label="Breaking market news" checked={breaking} onChange={setBreaking} />
          <Toggle label="Other alerts (recommendations, calendar)" checked={alerts} onChange={setAlerts} />
        </div>
      </div>

      <div>
        <Toggle label="Quiet hours (hold non-urgent pushes)" checked={quietOn} onChange={setQuietOn} />
        {quietOn && (
          <div className="mt-2 flex items-center gap-2 pl-1 text-xs text-text-secondary">
            <span>From</span>
            <HourSelect value={quietStart} onChange={setQuietStart} />
            <span>to</span>
            <HourSelect value={quietEnd} onChange={setQuietEnd} />
            <span className="text-text-secondary/70">(local · critical alerts still come through)</span>
          </div>
        )}
      </div>

      <div>
        <h4 className="text-xs font-semibold uppercase tracking-wide text-text-secondary">Muted tickers</h4>
        <p className="mt-0.5 text-[11px] text-text-secondary">No pushes for news about only these symbols.</p>
        <div className="mt-2 flex items-center gap-2">
          <input
            value={tickerInput}
            onChange={(e) => setTickerInput(e.target.value.toUpperCase())}
            onKeyDown={(e) => e.key === "Enter" && addTicker()}
            placeholder="e.g. TSLA"
            maxLength={12}
            className="w-28 rounded-lg border border-border bg-transparent px-2.5 py-1.5 font-mono text-sm uppercase text-text-primary outline-none focus:border-accent"
          />
          <button
            type="button"
            onClick={addTicker}
            className="rounded-lg border border-border px-2.5 py-1.5 text-xs font-medium text-text-primary hover:border-accent"
          >
            Mute
          </button>
        </div>
        {muted.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1.5">
            {muted.map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setMuted(muted.filter((x) => x !== t))}
                className="flex items-center gap-1 rounded-md bg-border/40 px-2 py-0.5 font-mono text-[11px] text-text-primary hover:bg-losses/15 hover:text-losses"
                title="Unmute"
              >
                {t} <span aria-hidden>✕</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={save}
          disabled={saving}
          className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {saving ? "Saving…" : "Save preferences"}
        </button>
        {savedAt && !saving && <span className="text-xs text-gains">✓ Saved</span>}
        {error && <span className="text-xs text-losses">{error}</span>}
      </div>
    </div>
  );
}

function Toggle({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      onClick={() => onChange(!checked)}
      className="flex items-center justify-between gap-3 text-left"
    >
      <span className="text-sm text-text-primary">{label}</span>
      <span
        className={`relative h-5 w-9 shrink-0 rounded-full transition ${checked ? "bg-accent" : "bg-border"}`}
      >
        <span
          className={`absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all ${checked ? "left-4" : "left-0.5"}`}
        />
      </span>
    </button>
  );
}

function HourSelect({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(Number(e.target.value))}
      className="rounded-md border border-border bg-transparent px-2 py-1 text-xs text-text-primary outline-none focus:border-accent"
    >
      {Array.from({ length: 24 }, (_, h) => (
        <option key={h} value={h}>
          {formatHour(h)}
        </option>
      ))}
    </select>
  );
}

function formatHour(h: number): string {
  const ampm = h < 12 ? "AM" : "PM";
  const hour = h % 12 === 0 ? 12 : h % 12;
  return `${hour} ${ampm}`;
}
