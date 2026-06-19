import { Skeleton } from "@/components/ui/Skeleton";

export default function AgentsPage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <h1 className="text-2xl font-bold tracking-tight lg:text-3xl">Agents</h1>

      <section className="rounded-xl border border-border bg-surface p-6">
        <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Agent Status
        </h2>
        <div className="space-y-3 font-mono">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-4 w-1/2" />
          <Skeleton className="h-4 w-3/5" />
        </div>
      </section>
    </div>
  );
}
