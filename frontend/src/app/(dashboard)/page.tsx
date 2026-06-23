import { AlertCards } from "@/components/dashboard/AlertCards";
import { AllocationChart } from "@/components/dashboard/AllocationChart";
import { HealthScoreRing } from "@/components/dashboard/HealthScoreRing";
import { MoversBubbles } from "@/components/dashboard/MoversBubbles";
import { PortfolioHero } from "@/components/dashboard/PortfolioHero";
import { PortfolioTrendChart } from "@/components/dashboard/PortfolioTrendChart";
import { UpcomingEvents } from "@/components/dashboard/UpcomingEvents";
import { MotionCard } from "@/components/ui/MotionCard";

/**
 * Home — the design-prototype dashboard. Visual-first, populated with dummy
 * data (see lib/mockData). Animated hero value, radial health gauge, sector
 * donut, 30-day trend, top movers, and a live-alerts feed.
 */
export default function Home() {
  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-text-primary">Good morning, Gaurav</h1>
        <p className="text-sm text-text-secondary">Here&apos;s how your book is doing today.</p>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-6">
        {/* Hero spans wide; health ring beside it */}
        <MotionCard index={0} className="min-h-[200px] md:col-span-4">
          <PortfolioHero />
        </MotionCard>
        <MotionCard index={1} className="min-h-[200px] md:col-span-2">
          <HealthScoreRing />
        </MotionCard>

        {/* Trend + allocation */}
        <MotionCard index={2} className="min-h-[240px] md:col-span-4">
          <PortfolioTrendChart />
        </MotionCard>
        <MotionCard index={3} className="min-h-[240px] md:col-span-2">
          <AllocationChart />
        </MotionCard>

        {/* Movers + alerts */}
        <MotionCard index={4} className="md:col-span-3" interactive={false}>
          <MoversBubbles />
        </MotionCard>
        <MotionCard index={5} className="md:col-span-3" interactive={false}>
          <AlertCards />
        </MotionCard>

        {/* Upcoming economic calendar (Epic 5 — real data) */}
        <MotionCard index={6} className="md:col-span-6" interactive={false}>
          <UpcomingEvents />
        </MotionCard>
      </div>
    </div>
  );
}
