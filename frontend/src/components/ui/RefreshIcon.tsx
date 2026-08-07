import { cn } from "@/lib/utils";

/** Circular-arrow refresh icon, spinning while a manual refresh is in flight. Shared by any card with
 * a "refresh now" button (Market News, Breaking Alerts, Briefing). */
export function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={spinning ? "animate-spin" : ""}
      aria-hidden
    >
      <path d="M21 12a9 9 0 1 1-3-6.7" />
      <path d="M21 3v5h-5" />
    </svg>
  );
}

/** A simple "pull the latest now" button — icon + label, spins and disables while in flight. For
 * cards without Briefing's ETA/progress-bar needs (a plain re-fetch, not a slow model call). */
export function RefreshButton({
  onClick,
  refreshing,
  className,
}: {
  onClick: () => void;
  refreshing: boolean;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={refreshing}
      aria-label="Refresh"
      className={cn(
        "flex items-center gap-1 rounded-md px-2 py-1 text-[11px] font-medium text-accent transition hover:bg-accent/[0.08] disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
    >
      <RefreshIcon spinning={refreshing} />
      {refreshing ? "Refreshing…" : "Refresh"}
    </button>
  );
}
