import { cn } from "@/lib/utils";

/**
 * The Argus logo (L1 · Panoptes ring). Argus Panoptes was the hundred-eyed watchman who never
 * closed every eye at once: a central eye inside a ring of sixteen smaller ones. When `animated`, the
 * ring's eyes blink one after another so some are always open, and the centre eye blinks now and
 * then (CSS in globals.css; frozen under prefers-reduced-motion).
 *
 * Draws in `currentColor`, with the pupil cut out in `pupil` (default: the app background), so it
 * takes the surrounding text colour. Decorative by default; pass `title` when the mark stands alone
 * as the only label.
 */
const RING = Array.from({ length: 16 }, (_, i) => {
  const a = (i * 2 * Math.PI) / 16 - Math.PI / 2;
  return { cx: 50 + 42 * Math.cos(a), cy: 50 + 42 * Math.sin(a), delay: i * 0.3 };
});

export function ArgusMark({
  size = 40,
  animated = true,
  title,
  pupil = "var(--color-background)",
  className,
}: {
  size?: number;
  animated?: boolean;
  title?: string;
  pupil?: string;
  className?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 100 100"
      className={cn("shrink-0 overflow-visible", className)}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      {RING.map((d, i) => (
        <circle
          key={i}
          cx={d.cx.toFixed(2)}
          cy={d.cy.toFixed(2)}
          r={3.4}
          fill="currentColor"
          className={animated ? "argus-ring-eye" : undefined}
          style={animated ? { animationDelay: `${d.delay.toFixed(1)}s` } : undefined}
        />
      ))}
      <g className={animated ? "argus-blink" : undefined}>
        <path
          d="M20 50 Q50 24 80 50 Q50 76 20 50 Z"
          fill="none"
          stroke="currentColor"
          strokeWidth={4.5}
          strokeLinejoin="round"
        />
        <circle cx={50} cy={50} r={11} fill="currentColor" />
        <circle cx={50} cy={50} r={4.6} fill={pupil} />
      </g>
    </svg>
  );
}

/** The mark beside the VT323 "ARGUS" wordmark — the horizontal lockup used in app chrome. */
export function ArgusLockup({
  size = 36,
  animated = true,
  className,
  wordClassName,
}: {
  size?: number;
  animated?: boolean;
  className?: string;
  wordClassName?: string;
}) {
  return (
    <span className={cn("inline-flex items-center gap-2.5 text-accent", className)}>
      <ArgusMark size={size} animated={animated} />
      <span className={cn("font-display term-glow leading-none tracking-[0.06em]", wordClassName)}>ARGUS</span>
    </span>
  );
}
