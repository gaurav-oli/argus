"use client";

import { ThemeToggle } from "@/components/theme/ThemeToggle";
import { HealthScoreBadge } from "@/features/portfolio/HealthScoreBadge";
import { PrivacyToggle } from "@/features/privacy/PrivacyToggle";
import { Sensitive } from "@/features/privacy/Sensitive";
import { portfolio, usd } from "@/lib/mockData";

/**
 * Top bar — brand (mobile) + the real Portfolio Health Score (Story 3.8) and total value KPI
 * (value still mock on this branch), tap-to-reveal privacy (FR-36), and the theme switch.
 * Sensitive values are masked until revealed.
 */
export function TopBar() {
  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-surface px-4 lg:px-6">
      <div className="flex items-center gap-2 lg:hidden">
        <span className="inline-block h-2.5 w-2.5 rounded-full bg-accent" aria-hidden />
        <span className="text-lg font-bold tracking-tight">Argus</span>
      </div>

      <div className="flex flex-1 items-center justify-end gap-5 lg:gap-6">
        <HealthScoreBadge />
        <div className="flex flex-col items-end leading-tight">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Total Value
          </span>
          <Sensitive className="text-lg font-bold text-text-primary">
            <span className="font-mono text-lg font-bold tabular-nums text-text-primary">
              {usd(portfolio.totalValue)}
            </span>
          </Sensitive>
        </div>
        <PrivacyToggle />
        <ThemeToggle />
      </div>
    </header>
  );
}
