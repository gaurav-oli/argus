import { Skeleton } from "@/components/ui/Skeleton";

export default function HomePage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight lg:text-3xl">
          Good morning
        </h1>
        <p className="mt-1 text-sm text-text-secondary">
          Here&apos;s your morning briefing.
        </p>
      </div>

      <section className="rounded-xl border border-border bg-surface p-6">
        <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Morning Briefing
        </h2>
        <div className="space-y-3">
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      </section>

      <div className="grid gap-4 sm:grid-cols-2">
        <section className="rounded-xl border border-border bg-surface p-6">
          <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Top Movers
          </h2>
          <div className="space-y-3">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-4 w-1/3" />
          </div>
        </section>
        <section className="rounded-xl border border-border bg-surface p-6">
          <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Today&apos;s Calendar
          </h2>
          <div className="space-y-3">
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-3/4" />
          </div>
        </section>
      </div>
    </div>
  );
}
