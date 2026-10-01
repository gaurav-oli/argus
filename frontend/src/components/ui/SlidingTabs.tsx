"use client";

import { motion, useReducedMotion } from "motion/react";

export interface TabDef {
  value: string;
  label: string;
}

/**
 * A tab row whose active indicator glides between tabs via a shared `layoutId`, instead of just
 * swapping color — the same "magic motion" underline pattern Linear/Vercel use. One instance owns one
 * indicator: every tab shares `layoutId="<id>-indicator"`, so Motion animates its position/width between
 * renders automatically, no manual coordinate math.
 */
export function SlidingTabs({
  id,
  tabs,
  value,
  onChange,
  className = "",
}: {
  id: string;
  tabs: TabDef[];
  value: string;
  onChange: (v: string) => void;
  className?: string;
}) {
  const reduce = useReducedMotion();
  return (
    <div className={`flex gap-6 border-b border-border ${className}`} role="tablist">
      {tabs.map((t) => {
        const active = t.value === value;
        return (
          <button
            key={t.value}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(t.value)}
            className={`relative -mb-px shrink-0 whitespace-nowrap pb-2.5 pt-1 text-[13px] font-medium transition-colors ${
              active ? "text-text-primary" : "text-text-secondary hover:text-text-primary"
            }`}
          >
            {t.label}
            {active && (
              <motion.span
                layoutId={`${id}-indicator`}
                className="absolute inset-x-0 -bottom-px h-[2px] bg-accent"
                transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 500, damping: 40 }}
              />
            )}
          </button>
        );
      })}
    </div>
  );
}
