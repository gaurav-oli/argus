import { cn } from "@/lib/utils";

/**
 * Cohesive page header used across every dashboard route. An accent eyebrow ties the pages
 * together, the title scales up on large screens, and an optional `action` slot sits at the end.
 * Presentational + server-safe (no hooks).
 */
export function PageHeader({
  eyebrow,
  title,
  subtitle,
  action,
  className,
}: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <header className={cn("mb-6 flex items-end justify-between gap-4", className)}>
      <div className="min-w-0">
        {eyebrow && (
          <p className="mb-1.5 flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.18em] text-accent">
            <span className="h-1 w-1 rounded-full bg-accent" />
            {eyebrow}
          </p>
        )}
        <h1 className="font-display text-2xl font-bold text-text-primary lg:text-[2rem]">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-text-secondary">{subtitle}</p>}
      </div>
      {action && <div className="hidden shrink-0 sm:block">{action}</div>}
    </header>
  );
}
