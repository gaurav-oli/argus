import { HoldingsTreemap } from "@/components/dashboard/HoldingsTreemap";
import { PerformanceGauges } from "@/components/dashboard/PerformanceGauges";
import { PriceChart } from "@/components/dashboard/PriceChart";
import { MotionCard } from "@/components/ui/MotionCard";
import { ImportStatement } from "@/features/portfolio/ImportStatement";

/**
 * Portfolio — real holdings import (Story 3.1) sits above the design-prototype widgets
 * (PriceChart / HoldingsTreemap / PerformanceGauges still render dummy data; they get wired to
 * real positions in Stories 3.4–3.6).
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
          <ImportStatement />
        </MotionCard>
        <MotionCard index={1} className="md:col-span-4">
          <PriceChart />
        </MotionCard>
        <MotionCard index={2} className="md:col-span-2" interactive={false}>
          <PerformanceGauges />
        </MotionCard>
        <MotionCard index={3} className="md:col-span-6" interactive={false}>
          <HoldingsTreemap />
        </MotionCard>
      </div>
    </div>
  );
}
