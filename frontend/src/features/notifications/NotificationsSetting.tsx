"use client";

import { useState } from "react";

import { useWebPush } from "@/hooks/useWebPush";
import { testPush } from "@/lib/apiClient";
import { NotificationPreferences } from "@/features/notifications/NotificationPreferences";

/** Profile → Notifications: enable/disable Web Push on this device (Epic 8, FR-17). */
export function NotificationsSetting() {
  const { status, busy, error, enable, disable } = useWebPush();
  const [testing, setTesting] = useState(false);
  const [testMsg, setTestMsg] = useState<string | null>(null);

  async function onTest() {
    setTesting(true);
    setTestMsg(null);
    try {
      const r = await testPush();
      if (!r.configured) {
        setTestMsg("Push isn't configured on the server.");
      } else if (r.delivered > 0) {
        setTestMsg(`Sent to ${r.delivered} of ${r.devices} device(s) — check your phone.`);
      } else {
        setTestMsg(
          `Reached 0 of ${r.devices} device(s). Turn notifications off and on again to re-subscribe, then retry.`,
        );
      }
    } catch {
      setTestMsg("Couldn't send a test just now.");
    } finally {
      setTesting(false);
    }
  }

  if (status === "unsupported") {
    return (
      <p className="text-xs text-text-secondary">
        This browser doesn&apos;t support push notifications. On iPhone, add Argus to your Home
        Screen first, then enable them from there.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h3 className="text-sm font-medium text-text-primary">Push notifications</h3>
          <p className="mt-0.5 text-xs text-text-secondary">
            Critical alerts and your morning briefing, delivered to this device.
          </p>
        </div>
        {status === "subscribed" ? (
          <button
            type="button"
            onClick={disable}
            disabled={busy}
            className="shrink-0 rounded-xl border border-border px-3 py-2 text-sm font-medium text-text-primary transition hover:border-accent disabled:opacity-40"
          >
            {busy ? "…" : "Turn off"}
          </button>
        ) : (
          <button
            type="button"
            onClick={enable}
            disabled={busy || status === "denied" || status === "loading"}
            className="shrink-0 rounded-xl border border-accent/30 bg-accent/[0.08] px-3 py-2 text-sm font-medium text-accent transition hover:bg-accent/[0.14] disabled:opacity-40"
          >
            {busy ? "Enabling…" : "Enable notifications"}
          </button>
        )}
      </div>

      {status === "subscribed" && (
        <div className="flex items-center gap-3">
          <p className="text-xs text-gains">✓ Enabled on this device.</p>
          <button
            type="button"
            onClick={onTest}
            disabled={testing}
            className="rounded-lg border border-border px-2.5 py-1 text-[11px] font-medium text-text-primary transition hover:border-accent disabled:opacity-40"
          >
            {testing ? "Sending…" : "Send test"}
          </button>
        </div>
      )}
      {testMsg && <p className="text-xs text-text-secondary">{testMsg}</p>}
      {status === "denied" && (
        <p className="text-xs text-text-secondary">
          Notifications are blocked in your browser settings. Allow them for this site, then try again.
        </p>
      )}
      {status === "unconfigured" && (
        <p className="text-xs text-text-secondary">
          Push isn&apos;t configured on the server yet (no VAPID key set).
        </p>
      )}
      {error && (
        <p className="text-xs text-losses" role="alert">
          {error}
        </p>
      )}

      <NotificationPreferences />
    </div>
  );
}
