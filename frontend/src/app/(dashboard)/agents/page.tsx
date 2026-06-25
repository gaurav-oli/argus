import { AgentFleet } from "@/features/agents/AgentFleet";

/**
 * Agents — the live AI fleet (Epic 9, Story 9.1). Per-agent status, capture counts, last-run, and
 * the pipeline visualization, from the session-gated /api/agents/status endpoint.
 */
export default function AgentsPage() {
  return (
    <div className="mx-auto max-w-5xl">
      <header className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-text-primary lg:text-3xl">Agents</h1>
        <p className="text-sm text-text-secondary">Your AI fleet, working in real time.</p>
      </header>

      <AgentFleet />
    </div>
  );
}
