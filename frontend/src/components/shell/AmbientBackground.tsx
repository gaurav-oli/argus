"use client";

import { useReducedMotion } from "motion/react";

/**
 * Cinematic Glass backdrop — two slow-drifting glow blobs and a soft top-light gradient behind the
 * app chrome. Fixed and pointer-transparent; sits at -z-10 so it glows through the frosted cards.
 * Honors prefers-reduced-motion (blobs hold still).
 */
export function AmbientBackground() {
  const reduce = useReducedMotion();
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      {/* soft accent top-light for depth */}
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(130% 120% at 50% -15%, color-mix(in srgb, var(--color-accent) 9%, transparent), transparent 55%)",
        }}
      />
      <div
        className="absolute -left-48 -top-56 h-[46rem] w-[46rem] rounded-full"
        style={{
          background: "var(--glow-1)",
          filter: "blur(120px)",
          animation: reduce ? undefined : "drift-a 24s ease-in-out infinite",
        }}
      />
      <div
        className="absolute -bottom-64 -right-48 h-[44rem] w-[44rem] rounded-full"
        style={{
          background: "var(--glow-2)",
          filter: "blur(120px)",
          animation: reduce ? undefined : "drift-b 30s ease-in-out infinite",
        }}
      />
      <div
        className="absolute left-1/2 top-1/3 h-[34rem] w-[34rem] -translate-x-1/2 rounded-full"
        style={{
          background: "var(--glow-3)",
          filter: "blur(140px)",
          animation: reduce ? undefined : "drift-a 36s ease-in-out infinite",
        }}
      />
    </div>
  );
}
