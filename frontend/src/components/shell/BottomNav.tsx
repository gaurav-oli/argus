"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { cn } from "@/lib/utils";
import { isActive, visibleNavItems } from "./navItems";

/**
 * Mobile 5-tab bottom navigation (PRD §12). The shell layout shows this only below `lg`; the
 * desktop Sidebar replaces it at larger sizes. Terminal Noir: monospace uppercase labels, and the
 * active tab inverted to a solid amber block like a selected menu row.
 */
export function BottomNav() {
  const pathname = usePathname();
  const { demoMode } = useDemoMode();

  return (
    <nav
      aria-label="Primary"
      className="flex min-h-16 shrink-0 items-stretch gap-1 border-t border-border bg-surface px-1 pb-[env(safe-area-inset-bottom)] pt-1 font-mono lg:hidden"
    >
      {visibleNavItems(demoMode).map(({ label, href, Icon }) => {
        const active = isActive(pathname, href);
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "my-1 flex flex-1 flex-col items-center justify-center gap-1 text-[10px] uppercase tracking-wider transition-colors",
              active ? "bg-accent text-background" : "text-text-secondary hover:text-accent",
            )}
          >
            <Icon className="h-5 w-5 shrink-0" />
            <span className="whitespace-nowrap">{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
