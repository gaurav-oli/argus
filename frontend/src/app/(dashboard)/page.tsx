import { AlertCards } from "@/components/dashboard/AlertCards";
import { AllocationChart } from "@/components/dashboard/AllocationChart";
import { BriefingCard } from "@/components/dashboard/BriefingCard";
import { DegradedBanner } from "@/components/dashboard/DegradedBanner";
import { HealthScoreRing } from "@/components/dashboard/HealthScoreRing";
import { HomeHeader } from "@/components/dashboard/HomeHeader";
import { MarketNews } from "@/components/dashboard/MarketNews";
import { PortfolioHero } from "@/components/dashboard/PortfolioHero";
import { PortfolioTrendChart } from "@/components/dashboard/PortfolioTrendChart";
import { UpcomingEvents } from "@/components/dashboard/UpcomingEvents";
import { MotionCard } from "@/components/ui/MotionCard";

/**
 * Home — Private Bank Editorial skin. A serif display face for numerals and headings, thin
 * hairline rules standing in for card borders (no fills, no glow, no glass), and a horizontal
 * allocation bar in place of a donut. The `.editorial-theme` scope itself lives on the dashboard
 * shell (layout.tsx), so it applies app-wide; cards below the fold reveal on scroll (MotionCard
 * `reveal="viewport"`); all animation respects prefers-reduced-motion.
 */
export default function Home() {
  return (
    <div className="mx-auto max-w-6xl">
      <DegradedBanner />
      <HomeHeader
        eyebrow="Overview"
        title="Good morning, Gaurav"
        subtitle="Here's how your book is doing today."
      />

      <div className="grid grid-cols-1 gap-4 md:grid-cols-6">
        {/* Pinned morning briefing (Epic 8 — real data) */}
        <MotionCard index={0} className="md:col-span-6" interactive={false}>
          <BriefingCard />
        </MotionCard>

        {/* Curated news — one important story at a time, with a Gemma summary */}
        <MotionCard index={1} className="md:col-span-6" interactive={false} reveal="viewport">
          <MarketNews />
        </MotionCard>

        {/* Hero spans wide; health ring beside it */}
        <MotionCard index={2} className="min-h-[200px] md:col-span-4">
          <PortfolioHero />
        </MotionCard>
        <MotionCard index={3} className="min-h-[200px] md:col-span-2" reveal="viewport">
          <HealthScoreRing />
        </MotionCard>

        {/* Trend + allocation */}
        <MotionCard index={4} className="min-h-[240px] md:col-span-4" reveal="viewport">
          <PortfolioTrendChart />
        </MotionCard>
        <MotionCard index={5} className="min-h-[240px] md:col-span-2" reveal="viewport">
          <AllocationChart />
        </MotionCard>

        {/* Live alerts */}
        <MotionCard index={6} className="md:col-span-6" interactive={false} reveal="viewport">
          <AlertCards />
        </MotionCard>

        {/* Upcoming economic calendar (Epic 5 — real data) */}
        <MotionCard index={7} className="md:col-span-6" interactive={false} reveal="viewport">
          <UpcomingEvents />
        </MotionCard>
      </div>
    </div>
  );
}
