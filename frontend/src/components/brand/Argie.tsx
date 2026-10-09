"use client";

import { useReducedMotion } from "motion/react";
import { useMemo } from "react";
import { ARGIE_COLORS, ARGIE_SIZE, MOOD_LABEL, argieSprite, type ArgieLayer, type ArgieMood } from "@/lib/argie";
import { cn } from "@/lib/utils";

const LAYER_CLASS: Record<ArgieLayer, string | undefined> = {
  body: undefined,
  lid: "argie-lid",
  tear: "argie-tear",
  arm: "argie-arm",
};

/**
 * Argie, the Argus mascot, in one of six moods (lib/argie). Crisp pixel SVG; the whole body carries
 * the mood's motion (celebrate jumps, happy bobs, worried shivers, sad slumps, napping breathes), the
 * eye blinks now and then, and `effects` adds the mood's surroundings — falling 0/1 confetti when
 * celebrating, a rain cloud when sad, zzz when napping. Labelled for screen readers with the mood's
 * word; everything stops under reduced motion.
 */
export function Argie({
  mood,
  size = 96,
  effects = true,
  className,
}: {
  mood: ArgieMood;
  size?: number;
  effects?: boolean;
  className?: string;
}) {
  const reduce = useReducedMotion();
  const layers = useMemo(() => {
    const by: Record<ArgieLayer, { x: number; y: number; fill: string }[]> = { body: [], lid: [], tear: [], arm: [] };
    for (const [x, y, c, layer] of argieSprite(mood)) by[layer].push({ x, y, fill: ARGIE_COLORS[c] });
    return by;
  }, [mood]);
  const { w, h } = ARGIE_SIZE;
  const height = Math.round((size * (h + 2)) / (w + 2));

  return (
    <span className={cn("relative inline-grid place-items-center", className)} style={{ width: size, height: height + 12 }}>
      {effects && !reduce && <MoodEffects mood={mood} />}
      <svg
        viewBox={`-1 -1 ${w + 2} ${h + 2}`}
        width={size}
        height={height}
        role="img"
        aria-label={`Argie is ${MOOD_LABEL[mood].toLowerCase()}`}
        className={cn("argie overflow-visible", `argie-${mood}`)}
        shapeRendering="crispEdges"
      >
        <g className="argie-body">
          {(Object.keys(layers) as ArgieLayer[]).map((layer) =>
            layers[layer].length === 0 ? null : (
              <g key={layer} className={LAYER_CLASS[layer]}>
                {layers[layer].map((p, i) => (
                  <rect key={i} x={p.x} y={p.y} width={1.02} height={1.02} fill={p.fill} />
                ))}
              </g>
            ),
          )}
        </g>
      </svg>
    </span>
  );
}

const CLOUD = ["..xxx.....", ".xxxxx.xx.", "xxxxxxxxxx", "xxxxxxxxxx", ".xxxxxxxx."];
const CONFETTI = ["var(--color-accent)", "var(--color-gains)", "var(--color-warning)", "var(--color-text-primary)"];

function MoodEffects({ mood }: { mood: ArgieMood }) {
  if (mood === "celebrate") {
    return (
      <span aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        {Array.from({ length: 12 }, (_, i) => (
          <span
            key={i}
            className="argie-confetti absolute top-0 font-mono text-[10px] font-bold"
            style={
              {
                left: `${(i * 37) % 92}%`,
                color: CONFETTI[i % 4],
                "--r": `${(i % 2 ? 1 : -1) * (90 + i * 20)}deg`,
                animationDelay: `${-(i * 0.23).toFixed(2)}s`,
              } as React.CSSProperties
            }
          >
            {i % 3 ? 1 : 0}
          </span>
        ))}
      </span>
    );
  }
  if (mood === "sad") {
    return (
      <span aria-hidden className="pointer-events-none absolute inset-x-0 -top-3 flex flex-col items-center">
        <svg viewBox="0 0 10 5" width="44%" className="argie-cloud" shapeRendering="crispEdges">
          {CLOUD.flatMap((row, y) =>
            [...row].map((ch, x) =>
              ch === "x" ? <rect key={`${x}-${y}`} x={x} y={y} width={1.02} height={1.02} fill={y < 2 ? "#8e8a75" : "#6e6a5a"} /> : null,
            ),
          )}
        </svg>
        <span className="relative h-0 w-1/3">
          {Array.from({ length: 5 }, (_, i) => (
            <span
              key={i}
              className="argie-drop absolute top-0 h-1.5 w-0.5 bg-[#9fd3ff]"
              style={{ left: `${i * 24}%`, animationDelay: `${-(i * 0.19).toFixed(2)}s` }}
            />
          ))}
        </span>
      </span>
    );
  }
  if (mood === "napping") {
    return (
      <span aria-hidden className="pointer-events-none absolute right-0 top-0">
        {["z", "z", "Z"].map((z, i) => (
          <span
            key={i}
            className="argie-z absolute font-display text-text-secondary"
            style={{ fontSize: 12 + i * 5, top: 10 - i * 3, right: 4, animationDelay: `${-(i * 0.8)}s` }}
          >
            {z}
          </span>
        ))}
      </span>
    );
  }
  return null;
}
