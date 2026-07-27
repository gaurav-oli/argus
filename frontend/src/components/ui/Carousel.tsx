"use client";

import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { cn } from "@/lib/utils";

/** Above this many items, individual dot indicators get too small/dense to be legible or usefully
 * tappable — fall back to a numeric "x / n" readout instead. */
const MAX_DOTS = 12;

interface CarouselProps<T> {
  items: T[];
  keyOf: (item: T, index: number) => string | number;
  renderItem: (item: T, index: number, active: boolean) => ReactNode;
  className?: string;
}

/**
 * Material 3 "hero carousel" pattern (https://m3.material.io/components/carousel/overview):
 * horizontally snap-scrolling items sized narrower than the container so the next item always
 * peeks at the edge, with the focused item emphasized (scaled up, full opacity) as you scroll —
 * instead of stacking every item vertically in a grid. Generic so any card list can opt in
 * without duplicating the scroll-tracking/gesture logic.
 */
export function Carousel<T>({ items, keyOf, renderItem, className }: CarouselProps<T>) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [active, setActive] = useState(0);
  const reduce = useReducedMotion();

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    let raf = 0;
    const updateActive = () => {
      const children = Array.from(track.children) as HTMLElement[];
      let closest = 0;
      let closestDist = Infinity;
      children.forEach((child, i) => {
        const dist = Math.abs(child.offsetLeft - track.scrollLeft);
        if (dist < closestDist) {
          closestDist = dist;
          closest = i;
        }
      });
      setActive(closest);
    };
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(updateActive);
    };
    track.addEventListener("scroll", onScroll, { passive: true });
    updateActive();
    return () => {
      track.removeEventListener("scroll", onScroll);
      cancelAnimationFrame(raf);
    };
  }, [items.length]);

  function scrollToIndex(i: number) {
    const track = trackRef.current;
    const child = track?.children[i] as HTMLElement | undefined;
    if (!track || !child) return;
    track.scrollTo({ left: child.offsetLeft, behavior: reduce ? "auto" : "smooth" });
  }

  if (items.length === 0) return null;

  return (
    <div className={cn("relative", className)}>
      <div
        ref={trackRef}
        className="flex snap-x snap-mandatory gap-4 overflow-x-auto scroll-smooth pb-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {items.map((item, i) => (
          <motion.div
            key={keyOf(item, i)}
            className="shrink-0 snap-start"
            style={{ width: "min(86vw, 420px)", transformOrigin: "left center" }}
            animate={reduce ? undefined : { scale: i === active ? 1 : 0.94, opacity: i === active ? 1 : 0.72 }}
            transition={{ type: "spring", stiffness: 300, damping: 30 }}
          >
            {renderItem(item, i, i === active)}
          </motion.div>
        ))}
      </div>

      {items.length > 1 && (
        <>
          <button
            type="button"
            aria-label="Previous"
            onClick={() => scrollToIndex(Math.max(0, active - 1))}
            disabled={active === 0}
            className="absolute left-0 top-1/2 hidden -translate-x-3 -translate-y-1/2 items-center justify-center rounded-full border border-border bg-surface/90 p-2 text-text-secondary shadow-sm backdrop-blur transition-opacity hover:text-text-primary disabled:pointer-events-none disabled:opacity-0 sm:flex"
          >
            <ChevronIcon direction="left" />
          </button>
          <button
            type="button"
            aria-label="Next"
            onClick={() => scrollToIndex(Math.min(items.length - 1, active + 1))}
            disabled={active === items.length - 1}
            className="absolute right-0 top-1/2 hidden translate-x-3 -translate-y-1/2 items-center justify-center rounded-full border border-border bg-surface/90 p-2 text-text-secondary shadow-sm backdrop-blur transition-opacity hover:text-text-primary disabled:pointer-events-none disabled:opacity-0 sm:flex"
          >
            <ChevronIcon direction="right" />
          </button>

          {items.length <= MAX_DOTS ? (
            <div className="mt-3 flex items-center justify-center gap-1.5">
              {items.map((item, i) => (
                <button
                  type="button"
                  key={keyOf(item, i)}
                  aria-label={`Go to item ${i + 1}`}
                  aria-current={i === active}
                  onClick={() => scrollToIndex(i)}
                  className={cn(
                    "h-1.5 rounded-full transition-all",
                    i === active ? "w-5 bg-accent" : "w-1.5 bg-border",
                  )}
                />
              ))}
            </div>
          ) : (
            // Too many items for individual dots to stay legible — a position readout instead,
            // paired with the arrow buttons (both already support full keyboard/scroll navigation).
            <p className="mt-3 text-center text-[11px] tabular-nums text-text-secondary">
              {active + 1} / {items.length}
            </p>
          )}
        </>
      )}
    </div>
  );
}

function ChevronIcon({ direction }: { direction: "left" | "right" }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={direction === "left" ? "M15 18l-6-6 6-6" : "M9 6l6 6-6 6"} />
    </svg>
  );
}
