"use client";

import { getSourceCredibility, getStrangerAlerts, type SourceCredibilityItem, type StrangerAlertItem } from "@/lib/apiClient";
import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Skeleton } from "@/components/ui/Skeleton";
import { riskColorClass } from "@/lib/scoreBands";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { motion } from "motion/react";
import { useMemo, useState } from "react";
import { useAutoRefresh } from "@/lib/useAutoRefresh";

function tier(t: string): { text: string; bar: string } {
  switch (t) {
    case "PLATINUM":
    case "GOLD":
      return { text: "text-gains", bar: "bg-gains" };
    case "SILVER":
    case "BRONZE":
      return { text: "text-text-secondary", bar: "bg-text-secondary" };
    case "FLAGGED":
      return { text: "text-warning", bar: "bg-warning" };
    default:
      return { text: "text-losses", bar: "bg-losses" };
  }
}

/**
 * Agent 1's source-trust ledger and Agent 1's Stranger Danger pump-and-dump watch, together — how much
 * Argus trusts where its news comes from, and what it's flagging outside your known universe. Reference
 * material, not something you check daily, which is exactly why it lives in Agent Labs rather than Today.
 */
export function TrustPanel() {
  const [sources, setSources] = useState<SourceCredibilityItem[] | null>(null);
  const [strangers, setStrangers] = useState<StrangerAlertItem[] | null>(null);

  useAutoRefresh(() => {
    getSourceCredibility()
      .then(setSources)
      .catch(() => setSources((prev) => prev ?? []));
    getStrangerAlerts()
      .then(setStrangers)
      .catch(() => setStrangers((prev) => prev ?? []));
  });

  const logos = useCompanyLogos(useMemo(() => (strangers ?? []).map((a) => a.ticker), [strangers]));

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h2 className="font-display text-lg font-bold text-text-primary">Trust &amp; Sources</h2>
        <p className="mt-1 max-w-[62ch] text-xs text-text-secondary">
          How much Argus trusts where its news comes from, and the pump-and-dump watch over anything outside your known
          universe.
        </p>
      </div>

      {sources === null ? (
        <Skeleton className="h-32 w-full" />
      ) : (
        <ul className="flex flex-col gap-2.5">
          {sources.map((s, i) => {
            const t = tier(s.tier);
            return (
              <motion.li
                key={s.source}
                initial={{ opacity: 0, x: -6 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.03 }}
                className="flex items-center gap-3"
              >
                <span className="w-40 truncate text-sm text-text-primary">{s.source}</span>
                <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-[var(--hover-wash)]">
                  <motion.div
                    className={`h-full rounded-full ${t.bar}`}
                    initial={{ width: 0 }}
                    animate={{ width: `${s.score}%` }}
                    transition={{ type: "spring", stiffness: 80, damping: 18, delay: 0.1 + i * 0.03 }}
                  />
                </div>
                <span className="w-8 text-right text-xs tabular-nums text-text-secondary">{s.score}</span>
                <span className={`w-20 text-right text-[11px] font-medium ${t.text}`}>{s.blocked ? "BLOCKED" : s.tier}</span>
              </motion.li>
            );
          })}
        </ul>
      )}

      {strangers && strangers.length > 0 && (
        <div className="border-t border-border pt-6">
          <p className="mb-3 text-[11px] font-semibold uppercase tracking-wide text-text-secondary">Stranger Danger — pump &amp; dump watch</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {strangers.map((a, i) => (
              <motion.div
                key={a.ticker}
                initial={{ opacity: 0, scale: 0.97 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ delay: i * 0.05, type: "spring", stiffness: 260, damping: 22 }}
                className="rounded-lg border border-warning/30 bg-warning/[0.05] p-3"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <CompanyIcon ticker={a.ticker} logoUrl={logos[a.ticker]} title={a.ticker} size={18} />
                    <span className="text-sm font-semibold text-text-primary">{a.ticker}</span>
                  </div>
                  <span className={`text-sm font-bold tabular-nums ${riskColorClass(a.riskScore)}`}>
                    {a.riskScore}
                    <span className="ml-0.5 text-[11px] font-normal text-text-secondary">/100 risk</span>
                  </span>
                </div>
                <p className="mt-1 text-xs text-text-secondary">
                  {a.coverageCount} articles · needs {a.requiredConsensus}/7 agents to recommend
                </p>
              </motion.div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
