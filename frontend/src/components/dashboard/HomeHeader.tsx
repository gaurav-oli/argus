"use client";

import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";

/**
 * Home-only header — Private Bank Editorial skin. Small-caps gold eyebrow, serif title, and a
 * thin gold rule that draws in beneath the whole block instead of a boxed card. Kept separate
 * from the shared PageHeader (used by every other route) so this trial doesn't change other pages.
 */
export function HomeHeader({
  eyebrow,
  title,
  subtitle,
  className,
}: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  className?: string;
}) {
  const reduce = useReducedMotion();
  return (
    <header className={cn("mb-6 pb-5", className)}>
      {eyebrow && (
        <p className="mb-2 text-[11px] font-semibold uppercase tracking-[0.28em] text-accent">{eyebrow}</p>
      )}
      <motion.h1
        className="font-serif-editorial text-3xl font-normal text-text-primary lg:text-4xl"
        initial={reduce ? false : { opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.5, ease: "easeOut" }}
      >
        {title}
      </motion.h1>
      {subtitle && <p className="mt-2 text-sm text-text-secondary">{subtitle}</p>}
      <div className="rule-draw mt-5 h-px bg-[var(--hairline)]" />
    </header>
  );
}
