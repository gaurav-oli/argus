"use client";

import { MotionCard } from "@/components/ui/MotionCard";
import { PageHeader } from "@/components/ui/PageHeader";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { EmptyPortfolio } from "@/features/portfolio/EmptyPortfolio";
import { HoldingsTable } from "@/features/portfolio/HoldingsTable";
import { ImportStatementDialog } from "@/features/portfolio/ImportStatementDialog";
import { PortfolioOverview } from "@/features/portfolio/PortfolioOverview";
import { PortfolioValue } from "@/features/portfolio/PortfolioValue";
import { getCash, getPortfolioValue } from "@/lib/apiClient";
import { subscribeToTopic } from "@/lib/wsClient";
import { useEffect, useState } from "react";

/** Null while the first check is still in flight (avoids a flash of the wrong state); then true
 *  only once BOTH positions and cash come back empty — matches HoldingsTable's own empty check. */
function usePortfolioIsEmpty(): boolean | null {
  const [isEmpty, setIsEmpty] = useState<boolean | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([getPortfolioValue(), getCash()])
      .then(([snap, cash]) => active && setIsEmpty(snap.positions.length === 0 && cash.length === 0))
      .catch(() => active && setIsEmpty(false)); // unsure beats hiding a real portfolio on a glitch
    const handle = subscribeToTopic<{ positions: unknown[] }>("/user/queue/portfolio", (snap) =>
      setIsEmpty((prev) => (snap.positions.length > 0 ? false : prev)),
    );
    return () => {
      active = false;
      handle.disconnect();
    };
  }, []);

  return isEmpty;
}

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
  const { demoMode, loaded } = useDemoMode();
  const isEmpty = usePortfolioIsEmpty();

  if (demoMode) {
    return (
      <div className="mx-auto max-w-6xl">
        <PageHeader eyebrow="Holdings" title="Portfolio" subtitle="Hidden while Demo Mode is on." />
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border px-6 py-16 text-center">
          <span className="font-mono text-2xl tracking-widest text-text-secondary select-none">••••••</span>
          <p className="text-sm text-text-secondary">
            Portfolio is hidden while Demo Mode is on — turn it off in Profile to view.
          </p>
        </div>
      </div>
    );
  }

  // Avoid a flash of real data before the initial demo-mode GET resolves, or of the full dashboard
  // grid (with its own bare "no holdings" text) before we know whether to show the empty state instead.
  if (!loaded || isEmpty === null) {
    return <div className="mx-auto max-w-6xl" />;
  }

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

      {isEmpty ? (
        <EmptyPortfolio onImport={() => setImportOpen(true)} />
      ) : (
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
      )}

      <ImportStatementDialog open={importOpen} onClose={() => setImportOpen(false)} />
    </div>
  );
}
