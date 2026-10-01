"use client";

import { motion, useMotionTemplate, useReducedMotion, useSpring } from "motion/react";
import type { PointerEvent } from "react";

/**
 * A subtle magnetic tilt on hover — the card leans toward the cursor with spring physics, plus a soft
 * light that follows the pointer. Reserved for the handful of cards that actually deserve the attention
 * (today's actionable calls), not applied everywhere — a tilt on every row would just be noise.
 */
export function TiltCard({
  children,
  className = "",
  onClick,
}: {
  children: React.ReactNode;
  className?: string;
  onClick?: () => void;
}) {
  const reduce = useReducedMotion();
  const rx = useSpring(0, { stiffness: 220, damping: 18 });
  const ry = useSpring(0, { stiffness: 220, damping: 18 });
  const mx = useSpring(50, { stiffness: 220, damping: 24 });
  const my = useSpring(50, { stiffness: 220, damping: 24 });
  const glow = useMotionTemplate`radial-gradient(180px circle at ${mx}% ${my}%, color-mix(in srgb, var(--color-accent) 12%, transparent), transparent 70%)`;

  function handleMove(e: PointerEvent<HTMLDivElement>) {
    if (reduce) return;
    const r = e.currentTarget.getBoundingClientRect();
    const px = (e.clientX - r.left) / r.width;
    const py = (e.clientY - r.top) / r.height;
    ry.set((px - 0.5) * 10);
    rx.set((0.5 - py) * 10);
    mx.set(px * 100);
    my.set(py * 100);
  }
  function handleLeave() {
    rx.set(0);
    ry.set(0);
    mx.set(50);
    my.set(50);
  }

  return (
    <motion.div
      onPointerMove={handleMove}
      onPointerLeave={handleLeave}
      onClick={onClick}
      style={reduce ? undefined : { rotateX: rx, rotateY: ry, transformPerspective: 700 }}
      whileHover={reduce ? undefined : { scale: 1.015 }}
      transition={{ type: "spring", stiffness: 300, damping: 22 }}
      className={`relative overflow-hidden ${className}`}
    >
      {!reduce && <motion.div className="pointer-events-none absolute inset-0" style={{ background: glow }} />}
      <div className="relative">{children}</div>
    </motion.div>
  );
}
