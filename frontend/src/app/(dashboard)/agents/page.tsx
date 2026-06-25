import { PageHeader } from "@/components/ui/PageHeader";
import { AgentFleet } from "@/features/agents/AgentFleet";

/**
 * Agents — the live AI fleet (Epic 9, Story 9.1). Per-agent status, capture counts, last-run, and
 * the pipeline visualization, from the session-gated /api/agents/status endpoint.
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
    </div>
  );
}
