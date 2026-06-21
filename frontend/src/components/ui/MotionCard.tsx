"use client";

import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";

/**
 * Premium surface card — gradient surface, hairline border, soft depth, and a
 * lift-on-hover micro-interaction. Each card self-animates its entrance with an
 * index-based delay so a panel cascades in (no parent-variant dependency).
 */
export function MotionCard({
  className,
  children,
  interactive = true,
  index = 0,
  entrance = "fade",
  ...rest
}: React.ComponentProps<typeof motion.div> & {
  interactive?: boolean;
  index?: number;
  /** "none" renders visible immediately — use on pages with heavy persistent animation. */
  entrance?: "fade" | "none";
}) {
  const reduce = useReducedMotion();
  const skip = reduce || entrance === "none";
  return (
    <motion.div
      initial={skip ? false : { opacity: 0, y: 16, scale: 0.985 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={skip ? { duration: 0 } : { delay: index * 0.07, type: "spring", stiffness: 120, damping: 18 }}
      whileHover={interactive && !reduce ? { y: -4, transition: { type: "spring", stiffness: 300, damping: 20 } } : undefined}
      className={cn(
        "group relative overflow-hidden rounded-2xl border border-[var(--hairline)] p-5",
        "bg-gradient-to-b from-surface to-surface/60",
        // subtle top sheen + soft depth; both adapt via the hairline/shadow tokens
        "shadow-[0_1px_0_0_var(--hairline)_inset,0_18px_40px_-22px_rgba(2,6,23,0.45)]",
        className,
      )}
      {...rest}
    >
      {children}
    </motion.div>
  );
}
