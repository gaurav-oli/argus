"use client";

import { useCallback } from "react";
import confetti from "canvas-confetti";

/**
 * Brand-coloured celebration burst — fired on happy moments (e.g. portfolio up
 * on the day). Respects `prefers-reduced-motion` via the library's own guard.
 */
export function useConfetti() {
  return useCallback((origin: { x?: number; y?: number } = {}) => {
    const colors = ["#00D4FF", "#00FF88", "#E8E8F0"];
    const opts = {
      colors,
      disableForReducedMotion: true,
      scalar: 0.9,
      ticks: 180,
    };
    confetti({ ...opts, particleCount: 70, spread: 62, startVelocity: 42, origin: { x: 0.5, y: 0.32, ...origin } });
    // a second softer puff for depth
    confetti({ ...opts, particleCount: 40, spread: 100, startVelocity: 28, origin: { x: 0.5, y: 0.35, ...origin } });
  }, []);
}
