import { Skeleton } from "@/components/ui/Skeleton";

/**
 * Route-level loading UI for the dashboard group — shown during navigation /
 * server work. Demonstrates the reusable Skeleton (AC #4).
 */
export default function DashboardLoading() {
  return (
    <div
      role="status"
      aria-busy="true"
      className="mx-auto flex max-w-4xl flex-col gap-6"
    >
      <span className="sr-only">Loading…</span>
      <Skeleton className="h-8 w-48" />
      <section className="rounded-xl border border-border bg-surface p-6">
        <div className="space-y-3">
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
        </div>
      </section>
    </div>
  );
}
