import { TypedText } from "@/components/terminal/TypedText";
import { cn } from "@/lib/utils";

/**
 * Cohesive page header used across every dashboard route. Terminal Noir: the eyebrow becomes the
 * shell's working directory (`~/argus/portfolio $`), the title types itself out in the glowing
 * VT323 display face, and an optional `action` slot sits at the end. Server-safe itself; the
 * typewriter is a small client island.
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
    <header className={cn("mb-6 flex items-end justify-between gap-4 border-b border-[var(--hairline)] pb-4", className)}>
      <div className="min-w-0">
        {eyebrow && <p className="mb-1 font-mono text-xs text-accent/80">{promptFor(eyebrow)}</p>}
        <h1 className="font-display text-4xl text-accent lg:text-5xl">
          <TypedText text={title} caret={false} />
        </h1>
        {subtitle && <p className="mt-1 text-sm text-text-secondary">{subtitle}</p>}
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </header>
  );
}

/** "Agents & Ops" → "~/argus/agents-ops $" — the eyebrow as a working directory. */
export function promptFor(eyebrow: string): string {
  const slug = eyebrow
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "");
  return `~/argus/${slug} $`;
}
