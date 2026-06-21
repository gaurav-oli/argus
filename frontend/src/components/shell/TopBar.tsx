import { ThemeToggle } from "@/components/theme/ThemeToggle";
import { healthScore, portfolio, usd } from "@/lib/mockData";

/**
 * Top bar — brand (mobile) + always-visible Health Score and total value KPIs
 * (mock data on this design branch) + the light/dark theme switch.
 */
export function TopBar() {
  const scoreColor =
    healthScore.score >= 75 ? "text-gains" : healthScore.score >= 50 ? "text-accent" : "text-warning";

  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-surface px-4 lg:px-6">
      <div className="flex items-center gap-2 lg:hidden">
        <span className="inline-block h-2.5 w-2.5 rounded-full bg-accent" aria-hidden />
        <span className="text-lg font-bold tracking-tight">Argus</span>
      </div>

      <div className="flex flex-1 items-center justify-end gap-5 lg:gap-6">
        <div className="flex flex-col items-end leading-tight">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Health Score
          </span>
          <span className={`font-mono text-lg font-bold tabular-nums ${scoreColor}`}>
            {healthScore.score}
          </span>
        </div>
        <div className="flex flex-col items-end leading-tight">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Total Value
          </span>
          <span className="font-mono text-lg font-bold tabular-nums text-text-primary">
            {usd(portfolio.totalValue)}
          </span>
        </div>
        <ThemeToggle />
      </div>
    </header>
  );
}
