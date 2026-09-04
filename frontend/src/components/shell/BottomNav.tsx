"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { cn } from "@/lib/utils";
import { isActive, visibleNavItems } from "./navItems";

/**
 * Mobile 5-tab bottom navigation (PRD §12). The shell layout shows this only
 * below `lg`; the desktop Sidebar replaces it at larger sizes.
 */
export function BottomNav() {
  const pathname = usePathname();
  const { demoMode } = useDemoMode();

  return (
    <nav className="flex min-h-16 shrink-0 items-stretch border-t border-border bg-surface pb-[env(safe-area-inset-bottom)] lg:hidden">
      {visibleNavItems(demoMode).map(({ label, href, Icon }) => {
        const active = isActive(pathname, href);
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={cn(
              "flex flex-1 flex-col items-center justify-center gap-1 text-[11px] font-medium transition-colors",
              active ? "text-accent" : "text-text-secondary hover:text-text-primary",
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
