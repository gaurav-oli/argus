"use client";

import { motion, useReducedMotion } from "motion/react";
import { AnimatedNumber } from "./AnimatedNumber";

const R = 24;
const CIRC = 2 * Math.PI * R;

/**
 * A conviction score as a ring that draws itself in (stroke-dashoffset spring) with the number
 * counting up inside it, instead of a static progress bar. One motion read instead of two.
 */
export function ConvictionRing({ value, size = 58, tone = "accent" }: { value: number; size?: number; tone?: "accent" | "losses" }) {
  const reduce = useReducedMotion();
  const pct = Math.max(0, Math.min(100, value)) / 100;
  const color = tone === "losses" ? "var(--color-losses)" : "var(--color-accent)";
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox="0 0 58 58" style={{ transform: "rotate(-90deg)" }}>
        <circle cx="29" cy="29" r={R} fill="none" stroke="var(--hover-wash)" strokeWidth="4" />
        <motion.circle
          cx="29"
          cy="29"
          r={R}
          fill="none"
          stroke={color}
          strokeWidth="4"
          strokeLinecap="round"
          strokeDasharray={CIRC}
          initial={reduce ? false : { strokeDashoffset: CIRC }}
          animate={{ strokeDashoffset: CIRC * (1 - pct) }}
          transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 60, damping: 16, delay: 0.1 }}
        />
      </svg>
      <span className="absolute inset-0 flex items-center justify-center font-display text-[15px] font-bold tabular-nums text-text-primary">
        <AnimatedNumber value={value} />
      </span>
    </div>
  );
}
