"use client";

import { useEffect, useState } from "react";
import { AnimatedNumber } from "@/components/ui/AnimatedNumber";
import { Sensitive } from "@/features/privacy/Sensitive";
import { getHealthScore, type HealthScoreResult } from "@/lib/apiClient";
import { healthBand } from "@/lib/scoreBands";

/**
 * Portfolio "health score" (Story 3.8, /api/portfolio/health-score) — a plain typographic
 * reading rather than a gauge: the score counting up beside its band label. Colour shifts by
 * score band.
 */
export function HealthScoreRing() {
  const [health, setHealth] = useState<HealthScoreResult | null>(null);

  useEffect(() => {
    let active = true;
    getHealthScore().then((h) => active && setHealth(h)).catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const score = health?.score ?? 0;
  const band = healthBand(score);

  return (
    <div className="flex h-full flex-col">
      <h3 className="text-[11px] font-medium uppercase tracking-wider text-text-secondary">Health Score</h3>

      <div className="mt-2 flex flex-1 flex-col justify-center">
        <div className="flex items-baseline gap-2.5">
          <Sensitive className="text-5xl font-normal">
            <AnimatedNumber
              value={score}
              className="font-serif-editorial text-5xl font-normal text-text-primary"
            />
          </Sensitive>
          <span className="text-base text-text-secondary">· {band.label}</span>
        </div>
        <div className="mt-4 h-px w-full" style={{ backgroundColor: `color-mix(in srgb, ${band.colorVar} 45%, transparent)` }} />
      </div>
    </div>
  );
}
