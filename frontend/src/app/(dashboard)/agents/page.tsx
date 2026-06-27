import { PageHeader } from "@/components/ui/PageHeader";
import { AgentFleet } from "@/features/agents/AgentFleet";
import { AgentPerformance } from "@/features/agents/AgentPerformance";

/**
 * Agents — the live AI fleet (Epic 9, Story 9.1) plus Agent 5's performance record (Stories 9.2–9.4):
 * accuracy over time, per-agent contribution, and probability calibration. All from session-gated
 * /api/agents and /api/recommendations endpoints.
 */
export default function AgentsPage() {
  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        eyebrow="Operations"
        title="Agents"
        subtitle="Your AI fleet, working in real time."
      />
      <AgentFleet />

      <div className="mt-8">
        <h2 className="mb-4 font-display text-lg font-semibold text-text-primary">Do they earn their keep?</h2>
        <AgentPerformance />
      </div>
    </div>
  );
}
