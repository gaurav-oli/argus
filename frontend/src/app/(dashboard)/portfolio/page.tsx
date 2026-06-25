import { HoldingsTreemap } from "@/components/dashboard/HoldingsTreemap";
import { MotionCard } from "@/components/ui/MotionCard";
import { PageHeader } from "@/components/ui/PageHeader";
import { CorporateActions } from "@/features/portfolio/CorporateActions";
import { HoldingsTable } from "@/features/portfolio/HoldingsTable";
import { ImportStatement } from "@/features/portfolio/ImportStatement";
import { ManagePositions } from "@/features/portfolio/ManagePositions";
import { PortfolioChart } from "@/features/portfolio/PortfolioChart";
import { PortfolioValue } from "@/features/portfolio/PortfolioValue";

/**
 * Portfolio — live value (3.4) + import (3.1) + corporate actions (3.3) + holdings table (3.5) +
 * value chart (3.6) + a holdings heatmap, all on real positions.
 */
export default function PortfolioPage() {
  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        eyebrow="Holdings"
        title="Portfolio"
        subtitle="Value, allocation, and how each position is moving."
      />

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
        <MotionCard index={4} className="md:col-span-6" interactive={false}>
          <ManagePositions />
        </MotionCard>
        <MotionCard index={5} className="md:col-span-6">
          <PortfolioChart />
        </MotionCard>
        <MotionCard index={6} className="md:col-span-6" interactive={false}>
          <HoldingsTreemap />
        </MotionCard>
      </div>
    </div>
  );
}
