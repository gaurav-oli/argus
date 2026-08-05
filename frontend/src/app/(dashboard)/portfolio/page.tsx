"use client";

import { MotionCard } from "@/components/ui/MotionCard";
import { PageHeader } from "@/components/ui/PageHeader";
import { HoldingsTable } from "@/features/portfolio/HoldingsTable";
import { ImportStatementDialog } from "@/features/portfolio/ImportStatementDialog";
import { PortfolioOverview } from "@/features/portfolio/PortfolioOverview";
import { PortfolioValue } from "@/features/portfolio/PortfolioValue";
import { useState } from "react";

/**
 * Portfolio — live value (3.4) + the holdings ledger (3.5, with cash folded in) + a value/composition
 * toggle (3.6 chart + the treemap heatmap), all on real positions. Redesigned to cut the noise of the
 * previous layout, which rendered the same holdings at three simultaneous levels of detail (a rollup
 * table, per-account cards, per-position tables) plus two always-on visualisations plus a statement
 * import widget with permanent real estate despite being an occasional action: one collapsible ledger,
 * one toggled overview, and import moved into a header action + dialog (3.1).
 */
export default function PortfolioPage() {
  const [importOpen, setImportOpen] = useState(false);

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        eyebrow="Holdings"
        title="Portfolio"
        subtitle="Value, allocation, and how each position is moving."
        action={
          <button
            type="button"
            onClick={() => setImportOpen(true)}
            className="flex min-h-[44px] cursor-pointer items-center gap-2 rounded-lg border border-border px-4 py-2 text-sm font-medium text-text-primary transition-colors hover:border-accent hover:text-accent"
          >
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M8 2v8M4.5 6.5 8 10l3.5-3.5M2 12.5v1a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-1" />
            </svg>
            Import statement
          </button>
        }
      />

      <div className="grid grid-cols-1 gap-4 md:grid-cols-6">
        <MotionCard index={0} className="md:col-span-2" interactive={false}>
          <PortfolioValue />
        </MotionCard>
        <MotionCard index={1} className="md:col-span-4">
          <PortfolioOverview />
        </MotionCard>
        <MotionCard index={2} className="md:col-span-6" interactive={false}>
          <HoldingsTable />
        </MotionCard>
      </div>

      <ImportStatementDialog open={importOpen} onClose={() => setImportOpen(false)} />
    </div>
  );
}
