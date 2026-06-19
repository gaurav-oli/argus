import { Skeleton } from "@/components/ui/Skeleton";

/**
 * Right panel — live alerts + recommendations (PRD §12). Placeholder only;
 * the WebSocket-driven feed lands with the alerts/recommendations stories.
 * Desktop only — the shell layout hides it below `xl`.
 */
export function RightPanel() {
  return (
    <aside className="hidden h-full w-80 shrink-0 flex-col border-l border-border bg-surface xl:flex">
      <Section title="Live Alerts">
        {[0, 1, 2].map((i) => (
          <div key={i} className="space-y-2 rounded-lg bg-background p-3">
            <Skeleton className="h-3 w-3/4" />
            <Skeleton className="h-3 w-1/2" />
          </div>
        ))}
      </Section>

      <Section title="Recommendations">
        {[0, 1].map((i) => (
          <div key={i} className="space-y-2 rounded-lg bg-background p-3">
            <Skeleton className="h-3 w-2/3" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        ))}
      </Section>
    </aside>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="border-b border-border p-4 last:border-b-0">
      <h2 className="mb-3 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
        {title}
      </h2>
      <div className="space-y-2">{children}</div>
    </div>
  );
}
