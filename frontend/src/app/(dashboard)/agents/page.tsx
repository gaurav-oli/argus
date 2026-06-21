import { AgentActivity } from "@/components/dashboard/AgentActivity";
import { MotionCard } from "@/components/ui/MotionCard";

/**
 * Agents — design prototype (dummy data). Live agent pipeline visualization.
 */
export default function AgentsPage() {
  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-text-primary">Agents</h1>
        <p className="text-sm text-text-secondary">Your AI fleet, working in real time.</p>
      </header>

      <div className="grid grid-cols-1 gap-4">
        <MotionCard index={0} interactive={false} entrance="none">
          <AgentActivity />
        </MotionCard>
      </div>
    </div>
  );
}
