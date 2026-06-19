"use client";

import { useEffect, useRef } from "react";
import { useInView, useMotionValueEvent, useReducedMotion, useSpring } from "motion/react";

type Props = {
  value: number;
  format?: (n: number) => string;
  className?: string;
};

/**
 * Spring-driven count-up. Animates 0 → value once it scrolls into view, writing
 * directly to the DOM node (no re-render per frame). Reduced-motion users get
 * the final value immediately via the spring settling fast.
 */
export function AnimatedNumber({ value, format = (n) => Math.round(n).toString(), className }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const reduce = useReducedMotion();
  const inView = useInView(ref, { once: true, margin: "-40px" });
  const spring = useSpring(0, { mass: 0.8, stiffness: 70, damping: 16 });

  useEffect(() => {
    if (!inView) return;
    if (reduce) {
      if (ref.current) ref.current.textContent = format(value);
    } else {
      spring.set(value);
    }
  }, [inView, value, spring, reduce, format]);

  useMotionValueEvent(spring, "change", (latest) => {
    if (ref.current && !reduce) ref.current.textContent = format(latest);
  });

  return (
    <span ref={ref} className={className}>
      {reduce ? format(value) : format(0)}
    </span>
  );
}
