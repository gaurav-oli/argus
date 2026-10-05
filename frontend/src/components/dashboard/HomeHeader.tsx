"use client";

import { TypedText } from "@/components/terminal/TypedText";
import { promptFor } from "@/components/ui/PageHeader";
import { cn } from "@/lib/utils";

/**
 * Home-only header — Terminal Noir. The greeting types itself out after a shell prompt, in the
 * glowing VT323 face, with the caret left blinking (Home is where the session "starts"). Kept
 * separate from the shared PageHeader because Home's greeting is personal and keeps its caret.
 */
export function HomeHeader({
  eyebrow,
  title,
  subtitle,
  className,
}: {
  eyebrow?: string;
  title: string;
  subtitle?: string;
  className?: string;
}) {
  return (
    <header className={cn("mb-6 border-b border-[var(--hairline)] pb-5", className)}>
      {eyebrow && <p className="mb-1 font-mono text-xs text-accent/80">{promptFor(eyebrow)} ./greet</p>}
      <h1 className="font-display text-4xl text-accent lg:text-6xl">
        <TypedText text={title} />
      </h1>
      {subtitle && <p className="mt-2 text-sm text-text-secondary">{subtitle}</p>}
    </header>
  );
}
