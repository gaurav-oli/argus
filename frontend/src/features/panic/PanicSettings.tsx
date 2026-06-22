"use client";

import { useState } from "react";
import { SHAKE_ENABLED_KEY } from "./usePanicGestures";

// iOS 13+ gates DeviceMotion behind a permission requested from a user gesture.
type MotionPermissionApi = { requestPermission?: () => Promise<"granted" | "denied"> };

/**
 * Panic-mode settings (FR-37): long-press is always on; shake is opt-in (and on iOS needs motion
 * permission, requested here on enable). Stored client-side in localStorage (a device-local UI
 * preference, like the theme).
 */
export function PanicSettings() {
  // Lazy init from localStorage (client-only); the component is "use client" and only renders in
  // the authenticated shell, so window/localStorage exist by mount.
  const [shake, setShake] = useState(
    () => typeof localStorage !== "undefined" && localStorage.getItem(SHAKE_ENABLED_KEY) === "1",
  );
  const [note, setNote] = useState<string | null>(null);

  async function toggleShake(next: boolean) {
    setNote(null);
    if (next) {
      const api = window.DeviceMotionEvent as unknown as MotionPermissionApi;
      if (typeof api?.requestPermission === "function") {
        try {
          const result = await api.requestPermission();
          if (result !== "granted") {
            setNote("Motion access denied — shake can't be enabled on this device.");
            return;
          }
        } catch {
          setNote("Couldn't request motion access.");
          return;
        }
      }
      localStorage.setItem(SHAKE_ENABLED_KEY, "1");
    } else {
      localStorage.removeItem(SHAKE_ENABLED_KEY);
    }
    setShake(next);
    setNote("Saved — takes effect on the next app load.");
  }

  return (
    <div className="flex flex-col gap-3">
      <div>
        <h3 className="text-sm font-medium text-text-primary">Panic mode</h3>
        <p className="mt-1 text-xs text-text-secondary">
          Long-press anywhere to instantly blank the screen and lock Argus. Returning needs your PIN
          or Face ID.
        </p>
      </div>
      <label className="flex items-center gap-3 text-sm text-text-primary">
        <input
          type="checkbox"
          checked={shake}
          onChange={(e) => toggleShake(e.target.checked)}
          className="h-4 w-4 accent-[var(--color-accent)]"
        />
        Also trigger on shake
      </label>
      {note && <p className="text-xs text-text-secondary">{note}</p>}
    </div>
  );
}
