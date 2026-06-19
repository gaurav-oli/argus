import { Skeleton } from "@/components/ui/Skeleton";

/**
 * Top bar — brand (mobile) + always-visible Health Score and total value
 * placeholders. Real values arrive with the portfolio/health-score stories;
 * for now they render as skeletons.
 */
export function TopBar() {
  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-surface px-4 lg:px-6">
      <div className="flex items-center gap-2 lg:hidden">
        <span
          className="inline-block h-2.5 w-2.5 rounded-full bg-accent"
          aria-hidden
        />
        <span className="text-lg font-bold tracking-tight">Argus</span>
      </div>

      <div className="flex flex-1 items-center justify-end gap-6">
        <div className="flex flex-col items-end gap-1">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Health Score
          </span>
          <Skeleton className="h-6 w-12" />
        </div>
        <div className="flex flex-col items-end gap-1">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Total Value
          </span>
          <Skeleton className="h-6 w-28" />
        </div>
      </div>
    </header>
  );
}
