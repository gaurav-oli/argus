import { HoldingsTreemap } from "@/components/dashboard/HoldingsTreemap";
import { PerformanceGauges } from "@/components/dashboard/PerformanceGauges";
import { MotionCard } from "@/components/ui/MotionCard";
import { CorporateActions } from "@/features/portfolio/CorporateActions";
import { HoldingsTable } from "@/features/portfolio/HoldingsTable";
import { ImportStatement } from "@/features/portfolio/ImportStatement";
import { ManagePositions } from "@/features/portfolio/ManagePositions";
import { PortfolioChart } from "@/features/portfolio/PortfolioChart";
import { PortfolioValue } from "@/features/portfolio/PortfolioValue";

/**
 * Portfolio — live value (Story 3.4) + holdings import (3.1) + corporate actions (3.3) sit above
 * the design-prototype widgets (PriceChart / HoldingsTreemap / PerformanceGauges still render dummy
 * data; the holdings table + chart wire to real positions in Stories 3.5–3.6).
 */
export default function PortfolioPage() {
  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-text-primary">Portfolio</h1>
        <p className="text-sm text-text-secondary">Value, allocation, and how each position is moving.</p>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-6">
        <MotionCard index={0} className="md:col-span-6" interactive={false}>
          <PortfolioValue />
        </MotionCard>
        <MotionCard index={1} className="md:col-span-6" interactive={false}>
          <ImportStatement />
        </MotionCard>
        <MotionCard index={2} className="md:col-span-6" interactive={false}>
          <CorporateActions />
        </MotionCard>
        <MotionCard index={3} className="md:col-span-6" interactive={false}>
          <HoldingsTable />
        </MotionCard>
        <MotionCard index={7} className="md:col-span-6" interactive={false}>
          <ManagePositions />
        </MotionCard>
        <MotionCard index={4} className="md:col-span-4">
          <PortfolioChart />
        </MotionCard>
        <MotionCard index={5} className="md:col-span-2" interactive={false}>
          <PerformanceGauges />
        </MotionCard>
        <MotionCard index={6} className="md:col-span-6" interactive={false}>
          <HoldingsTreemap />
        </MotionCard>
      </div>
    </div>
  );
}
