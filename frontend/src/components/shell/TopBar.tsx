"use client";

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
 * tap-to-reveal privacy (FR-36). Sensitive values are masked until revealed.
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
      <div className="flex items-center gap-2 lg:hidden">
        <span className="inline-block h-2.5 w-2.5 rounded-full bg-accent" aria-hidden />
        <span className="font-serif-editorial text-lg font-normal tracking-tight">Argus</span>
      </div>

      <div className="flex flex-1 items-center justify-end gap-5 lg:gap-6">
        <HealthScoreBadge />
        <div className="flex flex-col items-end leading-tight">
          <span className="text-[11px] font-medium uppercase tracking-wide text-text-secondary">
            Total Value
          </span>
          <Sensitive className="text-lg font-normal text-text-primary">
            <span className="font-serif-editorial text-lg font-normal text-text-primary">
              {usdOrDash(totalValue)}
            </span>
          </Sensitive>
        </div>
        <button
          onClick={() => setChatOpen(true)}
          className="rounded-lg border border-accent/40 px-3 py-1.5 text-xs font-medium text-accent transition-colors hover:bg-accent/10"
        >
          Ask AI
        </button>
        <PrivacyToggle />
      </div>

      {chatOpen && <PortfolioChat onClose={() => setChatOpen(false)} />}
    </header>
  );
}
