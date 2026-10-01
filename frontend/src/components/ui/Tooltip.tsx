"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useState } from "react";

/**
 * A small hover/focus explainer for a status chip — exactly the "what does CHEAP mean" question a
 * one-word badge can't answer on its own. Shows on hover (mouse) and focus (keyboard), so it's reachable
 * either way; positioned above the trigger by default since these sit inside scrolling tables where
 * "below" risks getting clipped by the next row's hover state.
 */
export function Tooltip({
  content,
  children,
  side = "top",
}: {
  content: React.ReactNode;
  children: React.ReactNode;
  side?: "top" | "bottom";
}) {
  const [open, setOpen] = useState(false);
  const reduce = useReducedMotion();

  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      {children}
      <AnimatePresence>
        {open && (
          <motion.span
            role="tooltip"
            initial={reduce ? false : { opacity: 0, y: side === "top" ? 4 : -4, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: side === "top" ? 4 : -4, scale: 0.97 }}
            transition={{ duration: 0.12 }}
            className={`pointer-events-none absolute left-1/2 z-50 w-60 -translate-x-1/2 rounded-lg border border-border bg-elevated px-3 py-2 text-[11.5px] leading-relaxed text-text-secondary shadow-xl ${
              side === "top" ? "bottom-full mb-2" : "top-full mt-2"
            }`}
          >
            {content}
            <span
              className={`absolute left-1/2 h-2 w-2 -translate-x-1/2 rotate-45 border-border bg-elevated ${
                side === "top" ? "-bottom-1 border-b border-r" : "-top-1 border-l border-t"
              }`}
            />
          </motion.span>
        )}
      </AnimatePresence>
    </span>
  );
}
