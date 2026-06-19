import { Skeleton } from "@/components/ui/Skeleton";

export default function PortfolioPage() {
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <h1 className="text-2xl font-bold tracking-tight lg:text-3xl">Portfolio</h1>

      <section className="rounded-xl border border-border bg-surface p-6">
        <h2 className="mb-4 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
          Holdings
        </h2>
        <div className="space-y-3">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-5/6" />
          <Skeleton className="h-4 w-4/6" />
          <Skeleton className="h-4 w-3/6" />
        </div>
      </section>
    </div>
  );
}
