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
  reveal = "mount",
  ...rest
}: React.ComponentProps<typeof motion.div> & {
  interactive?: boolean;
  index?: number;
  /** "none" renders visible immediately — use on pages with heavy persistent animation. */
  entrance?: "fade" | "none";
  /** "viewport" replays the entrance each time the card scrolls into view instead of once on mount. */
  reveal?: "mount" | "viewport";
}) {
  const reduce = useReducedMotion();
  const skip = reduce || entrance === "none";
  const revealProps =
    reveal === "viewport"
      ? { whileInView: { opacity: 1, y: 0, scale: 1 }, viewport: { once: true, margin: "-60px" } }
      : { animate: { opacity: 1, y: 0, scale: 1 } };
  return (
    <motion.div
      initial={skip ? false : { opacity: 0, y: 16, scale: 0.985 }}
      {...revealProps}
      transition={skip ? { duration: 0 } : { delay: index * 0.07, type: "spring", stiffness: 120, damping: 18 }}
      whileHover={interactive && !reduce ? { y: -4, transition: { type: "spring", stiffness: 300, damping: 20 } } : undefined}
      className={cn(
        // Cinematic Glass surface — frosted fill, hairline, inner sheen, soft depth.
        "group relative overflow-hidden rounded-2xl p-5 glass",
        interactive && "glass-interactive",
        className,
      )}
      {...rest}
    >
      {children}
    </motion.div>
  );
}
