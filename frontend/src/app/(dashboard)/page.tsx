import { AlertCards } from "@/components/dashboard/AlertCards";
import { AllocationChart } from "@/components/dashboard/AllocationChart";
import { BriefingCard } from "@/components/dashboard/BriefingCard";
import { DegradedBanner } from "@/components/dashboard/DegradedBanner";
import { HomeHeader } from "@/components/dashboard/HomeHeader";
import { MarketNews } from "@/components/dashboard/MarketNews";
import { PortfolioTrendChart } from "@/components/dashboard/PortfolioTrendChart";
import { UpcomingEvents } from "@/components/dashboard/UpcomingEvents";
import { MotionCard } from "@/components/ui/MotionCard";

/**
 * Home — Private Bank Editorial skin. A serif display face for numerals and headings, thin
 * hairline rules standing in for card borders (no fills, no glow, no glass), and a horizontal
 * allocation bar in place of a donut. The `.editorial-theme` scope itself lives on the dashboard
 * shell (layout.tsx), so it applies app-wide; cards below the fold reveal on scroll (MotionCard
 * `reveal="viewport"`); all animation respects prefers-reduced-motion.
 *
 * Portfolio value and health score have their own compact readouts in the persistent TopBar (every
 * page, including this one) — the large PortfolioHero/HealthScoreRing cards that used to repeat the
 * same two numbers here were removed as pure duplication.
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

        {/* Curated news carousel — several important stories at a time, each with a Gemma summary */}
        <MotionCard index={1} className="md:col-span-6" interactive={false} reveal="viewport">
          <MarketNews />
        </MotionCard>

        {/* Trend + allocation */}
        <MotionCard index={2} className="min-h-[240px] md:col-span-4" reveal="viewport">
          <PortfolioTrendChart />
        </MotionCard>
        <MotionCard index={3} className="min-h-[240px] md:col-span-2" reveal="viewport">
          <AllocationChart />
        </MotionCard>

        {/* Live alerts */}
        <MotionCard index={4} className="md:col-span-6" interactive={false} reveal="viewport">
          <AlertCards />
        </MotionCard>

        {/* Upcoming economic calendar (Epic 5 — real data) */}
        <MotionCard index={5} className="md:col-span-6" interactive={false} reveal="viewport">
          <UpcomingEvents />
        </MotionCard>
      </div>
    </div>
  );
}
