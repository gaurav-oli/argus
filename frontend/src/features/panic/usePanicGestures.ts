"use client";

import { useEffect } from "react";

/** localStorage key for the shake-to-panic preference (off by default; long-press is always on). */
export const SHAKE_ENABLED_KEY = "argus-panic-shake";

const LONG_PRESS_MS = 600;
const SHAKE_THRESHOLD = 22; // m/s^2 of combined acceleration delta
const SHAKE_COOLDOWN_MS = 1000;

/**
 * Binds the panic-mode gestures (FR-37) and calls {@link onPanic} when triggered:
 * - **Long-press** anywhere (~600ms press-hold, cancelled by move/scroll/release) — always on.
 * - **Shake** (device motion) — only when the user enabled it (localStorage); on iOS this requires
 *   motion permission, requested separately from a Settings tap.
 */
export function usePanicGestures(onPanic: () => void) {
  useEffect(() => {
    let pressTimer: ReturnType<typeof setTimeout> | null = null;

    const clearPress = () => {
      if (pressTimer) {
        clearTimeout(pressTimer);
        pressTimer = null;
      }
    };
    const onPointerDown = () => {
      clearPress();
      pressTimer = setTimeout(onPanic, LONG_PRESS_MS);
    };

    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("pointerup", clearPress);
    window.addEventListener("pointermove", clearPress);
    window.addEventListener("pointercancel", clearPress);
    window.addEventListener("scroll", clearPress, true);

    // --- Shake (opt-in) ---
    let lastShake = 0;
    let last = { x: 0, y: 0, z: 0 };
    let primed = false;
    const shakeEnabled =
      typeof localStorage !== "undefined" && localStorage.getItem(SHAKE_ENABLED_KEY) === "1";

    const onMotion = (e: DeviceMotionEvent) => {
      const a = e.accelerationIncludingGravity;
      if (!a || a.x == null || a.y == null || a.z == null) return;
      if (!primed) {
        last = { x: a.x, y: a.y, z: a.z };
        primed = true;
        return;
      }
      const delta = Math.abs(a.x - last.x) + Math.abs(a.y - last.y) + Math.abs(a.z - last.z);
      last = { x: a.x, y: a.y, z: a.z };
      const now = Date.now();
      if (delta > SHAKE_THRESHOLD && now - lastShake > SHAKE_COOLDOWN_MS) {
        lastShake = now;
        onPanic();
      }
    };
    if (shakeEnabled && typeof window.DeviceMotionEvent !== "undefined") {
      window.addEventListener("devicemotion", onMotion);
    }

    return () => {
      clearPress();
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("pointerup", clearPress);
      window.removeEventListener("pointermove", clearPress);
      window.removeEventListener("pointercancel", clearPress);
      window.removeEventListener("scroll", clearPress, true);
      window.removeEventListener("devicemotion", onMotion);
    };
  }, [onPanic]);
}
