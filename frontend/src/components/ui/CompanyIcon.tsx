"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

/**
 * Company logo when cached, falling back to a colored initial (also covers broken/expired URLs).
 * Shared by Upcoming Events and the Holdings table.
 */
export function CompanyIcon({
  ticker,
  logoUrl,
  title,
  size = 28,
  className,
}: {
  ticker: string | null;
  logoUrl: string | null | undefined;
  title: string;
  /** Pixel size (square). Default 28 matches Upcoming Events; Holdings uses a smaller size. */
  size?: number;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const initial = (ticker ?? title).trim().charAt(0).toUpperCase() || "?";
  // Radius scales with size so it still reads as "rounded" at smaller sizes, capped at 10px
  // (the value tuned for the 28px default) so it doesn't balloon at larger sizes.
  const radius = Math.min(10, Math.round(size * 0.36));

  // Inline `borderRadius` rather than a `rounded-*` class: the editorial theme's global
  // sharp-corner rule (globals.css) flattens every `.rounded-*` Tailwind class to 2px, which
  // made this icon look practically square regardless of which rounding class was used. An
  // inline style has higher specificity than that class-based selector, so it isn't caught by it.
  if (logoUrl && !failed) {
    return (
      <img
        src={logoUrl}
        alt=""
        loading="lazy"
        onError={() => setFailed(true)}
        style={{ borderRadius: radius, height: size, width: size }}
        className={cn("shrink-0 object-contain", className)}
      />
    );
  }
  return (
    <span
      aria-hidden
      style={{ borderRadius: radius, height: size, width: size, fontSize: Math.max(9, size * 0.4) }}
      className={cn(
        "flex shrink-0 items-center justify-center bg-[var(--hover-wash)] font-semibold text-text-secondary",
        className,
      )}
    >
      {initial}
    </span>
  );
}
