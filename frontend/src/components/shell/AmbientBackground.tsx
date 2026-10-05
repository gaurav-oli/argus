"use client";

import { useReducedMotion } from "motion/react";

/**
 * Terminal Noir backdrop — a CRT tube: a faint amber centre bloom, a dark vignette toward the edges,
 * and horizontal scanlines with a slow, barely-there flicker. Fixed and pointer-transparent; the
 * scanlines sit ABOVE content (z-30) the way a real tube's raster does, at low enough opacity that
 * text stays fully readable. Honors prefers-reduced-motion (the flicker holds still).
 */
export function AmbientBackground() {
  const reduce = useReducedMotion();
  return (
    <>
      <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
        <div
          className="absolute inset-0"
          style={{
            background:
              "radial-gradient(ellipse 80% 70% at 50% 40%, rgba(255,176,0,0.07), transparent 60%), radial-gradient(ellipse at center, transparent 55%, rgba(0,0,0,0.65) 100%)",
          }}
        />
      </div>
      <div
        aria-hidden
        className="pointer-events-none fixed inset-0 z-30"
        style={{
          background:
            "repeating-linear-gradient(0deg, rgba(0,0,0,0.22) 0px, rgba(0,0,0,0.22) 1px, transparent 1px, transparent 3px)",
          opacity: 0.55,
          animation: reduce ? undefined : "term-flicker 7s linear infinite",
        }}
      />
    </>
  );
}
