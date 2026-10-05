"use client";

import { ArgusLockup } from "@/components/brand/ArgusMark";
import { PortfolioChat } from "@/features/conversation/PortfolioChat";
import { HealthScoreBadge } from "@/features/portfolio/HealthScoreBadge";
import { PrivacyToggle } from "@/features/privacy/PrivacyToggle";
import { Sensitive } from "@/features/privacy/Sensitive";
import { getPortfolioValue } from "@/lib/apiClient";
import { usdOrDash } from "@/lib/format";
import { useEffect, useState } from "react";

/**
 * Top bar — brand (mobile) + the real Portfolio Health Score (Story 3.8) and the real total value
 * KPI (Story 3.4, /api/portfolio/value), a global "Ask AI" portfolio-chat launcher (Story 7.2), and
 * tap-to-reveal privacy (FR-36). Sensitive values are masked until revealed. Terminal Noir: the value
 * reads in the glowing VT323 display face and "Ask AI" is a shell prompt (`argus> ask_`).
 */
export function TopBar() {
  const [chatOpen, setChatOpen] = useState(false);
  const [totalValue, setTotalValue] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    getPortfolioValue()
      .then((s) => active && setTotalValue(s.totalValueCad))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  return (
    <header className="glass-chrome sticky top-0 z-20 flex h-16 shrink-0 items-center justify-between border-b border-[var(--glass-border)] px-4 lg:px-6">
      <div className="flex items-center lg:hidden">
        <ArgusLockup size={28} wordClassName="text-2xl" />
      </div>

      <div className="flex flex-1 items-center justify-end gap-5 lg:gap-6">
        <HealthScoreBadge />
        <div className="flex flex-col items-end leading-tight">
          <span className="text-[10px] uppercase tracking-[0.18em] text-text-secondary">
            Total Value · CAD
          </span>
          <Sensitive className="text-lg font-normal text-text-primary">
            <span className="font-display text-2xl text-accent">
              {usdOrDash(totalValue)}
            </span>
          </Sensitive>
        </div>
        <button
          onClick={() => setChatOpen(true)}
          className="group flex min-h-9 items-center border border-accent/50 px-3 py-1.5 font-mono text-xs text-accent transition-colors hover:bg-accent hover:text-background"
        >
          <span aria-hidden>argus&gt;&nbsp;</span>ask
          <span className="term-caret group-hover:bg-background" aria-hidden />
        </button>
        <PrivacyToggle />
      </div>

      {chatOpen && <PortfolioChat onClose={() => setChatOpen(false)} />}
    </header>
  );
}
