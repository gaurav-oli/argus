"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { isActive, navItems } from "./navItems";

/**
 * Fixed left navigation — desktop only. The shell layout hides it below `lg`.
 */
export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="glass-chrome hidden h-full w-60 shrink-0 flex-col border-r border-[var(--glass-border)] lg:flex">
      <div className="flex h-16 items-center gap-2.5 px-6">
        <span
          className="inline-block h-2.5 w-2.5 rounded-full bg-accent"
          style={{ boxShadow: "0 0 12px var(--color-accent), 0 0 4px var(--color-accent)" }}
          aria-hidden
        />
        <span className="font-display text-lg font-bold tracking-tight text-text-primary">
          Argus
        </span>
      </div>

      <nav className="flex flex-1 flex-col gap-1 px-3 py-2">
        {navItems.map(({ label, href, Icon }) => {
          const active = isActive(pathname, href);
          return (
            <Link
              key={href}
              href={href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200",
                active
                  ? "bg-accent/12 text-accent shadow-[inset_0_0_0_1px_color-mix(in_srgb,var(--color-accent)_25%,transparent)]"
                  : "text-text-secondary hover:bg-[var(--hover-wash)] hover:text-text-primary",
              )}
            >
              <Icon className="h-5 w-5" />
              {label}
            </Link>
          );
        })}
      </nav>
    </aside>
  );
}
