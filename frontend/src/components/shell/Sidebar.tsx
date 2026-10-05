"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { ArgusLockup } from "@/components/brand/ArgusMark";
import { useDemoMode } from "@/features/privacy/DemoModeProvider";
import { cn } from "@/lib/utils";
import { isActive, visibleNavItems } from "./navItems";

/**
 * Fixed left navigation — desktop only (the shell layout hides it below `lg`). Terminal Noir: the
 * Argus logo and wordmark, numbered menu entries (`[1] HOME`) with the active one inverted to a
 * solid amber block, Alt+1…5 shortcuts to jump between them, and a shell prompt with a blinking
 * caret at the foot.
 */
export function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const { demoMode } = useDemoMode();
  const items = visibleNavItems(demoMode);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.altKey || e.ctrlKey || e.metaKey) return;
      // e.code, not e.key: on macOS Option+digit types a symbol (¡, ™, …) into e.key.
      const match = /^Digit([1-9])$/.exec(e.code);
      if (!match) return;
      const target = visibleNavItems(demoMode)[Number(match[1]) - 1];
      if (!target) return;
      e.preventDefault();
      router.push(target.href);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [demoMode, router]);

  return (
    <aside className="glass-chrome hidden h-full w-60 shrink-0 flex-col border-r border-[var(--glass-border)] lg:flex">
      <div className="flex h-16 items-center px-5">
        <ArgusLockup size={36} wordClassName="text-3xl" />
      </div>

      <nav aria-label="Primary" className="flex flex-1 flex-col gap-1 px-3 py-2 font-mono text-sm">
        {items.map(({ label, href, Icon }, i) => {
          const active = isActive(pathname, href);
          return (
            <Link
              key={href}
              href={href}
              aria-current={active ? "page" : undefined}
              aria-keyshortcuts={`Alt+${i + 1}`}
              className={cn(
                "group flex items-center gap-3 px-3 py-2.5 uppercase tracking-wider transition-colors duration-150",
                active
                  ? "bg-accent text-background"
                  : "text-text-secondary hover:bg-[var(--hover-wash)] hover:text-accent",
              )}
            >
              <span className={cn("text-xs", active ? "text-background" : "text-accent/70")}>[{i + 1}]</span>
              <Icon className="h-4 w-4" />
              {label}
            </Link>
          );
        })}
      </nav>

      <div className="border-t border-[var(--glass-border)] px-5 py-4 font-mono text-[11px] leading-relaxed text-text-secondary">
        <p>alt+1…{items.length} to jump</p>
        <p className="mt-1 text-accent">
          argus@mini:~$<span className="term-caret" aria-hidden />
        </p>
      </div>
    </aside>
  );
}
