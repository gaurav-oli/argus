import { cn } from "@/lib/utils";

/**
 * Terminal Noir split bar drawn in block characters — `██████████████░░░░░░` — the left share in
 * one color, the remainder in another. Used where the old UI drew a two-tone pill (e.g. bull/bear
 * odds). Pure text, so it scales with the font and never needs layout math; exposed to assistive
 * tech as a single labelled image.
 */
export function AsciiBar({
  percent,
  cells = 24,
  leftClassName = "text-gains",
  rightClassName = "text-losses/70",
  label,
  className,
}: {
  /** Share of the bar on the left, 0–100. */
  percent: number;
  cells?: number;
  leftClassName?: string;
  rightClassName?: string;
  label: string;
  className?: string;
}) {
  const clamped = Math.max(0, Math.min(100, percent));
  const filled = Math.round((clamped / 100) * cells);
  return (
    <span role="img" aria-label={label} className={cn("block overflow-hidden whitespace-nowrap font-mono leading-none", className)}>
      <span aria-hidden className={leftClassName}>
        {"█".repeat(filled)}
      </span>
      <span aria-hidden className={rightClassName}>
        {"░".repeat(cells - filled)}
      </span>
    </span>
  );
}
