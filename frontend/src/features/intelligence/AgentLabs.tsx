"use client";

import { AnimatePresence, motion } from "motion/react";
import { useState } from "react";
import { ChartStudyPanel } from "./ChartStudyPanel";
import { DeepAnalysisPanel } from "./DeepAnalysisPanel";
import { FilingsPanel, FundamentalsPanel } from "./CompanyReadsPanel";
import { LearningPanel } from "./LearningPanel";
import { StrategyLibraryPanel } from "./StrategyLibraryPanel";
import { TrustPanel } from "./TrustPanel";

const LABS = [
  { key: "deep", label: "Deep Analysis", sub: "Agent 11" },
  { key: "chart", label: "Chart Study", sub: "Agent 10" },
  { key: "fundamentals", label: "Fundamentals & Valuation", sub: "Agent 12" },
  { key: "filings", label: "Filings & Earnings", sub: "Agent 14" },
  { key: "strategies", label: "Academic Strategies", sub: "Agent 15" },
  { key: "learning", label: "Trade Learner", sub: "Agent 13" },
  { key: "trust", label: "Trust & Sources", sub: "Agents 1 & 4" },
] as const;

type LabKey = (typeof LABS)[number]["key"];

/**
 * The cross-ticker, agent-level views — Agent 11's scorecard, Agent 10's whole-universe chart scan, the
 * full Strategy library, and so on — as distinct from {@link TickerDetail}'s per-ticker tabs. A left rail
 * with a sliding highlight switches between them; the existing per-agent panels are mounted as-is (they
 * already fetch their own real data), just given a shared home instead of being stacked one after another.
 */
export function AgentLabs() {
  const [lab, setLab] = useState<LabKey>("deep");

  return (
    <div className="flex gap-8">
      <nav className="w-52 shrink-0">
        <p className="mb-2 text-[10px] font-semibold uppercase tracking-wide text-text-tertiary">Agent labs</p>
        <div className="flex flex-col gap-0.5">
          {LABS.map((l) => (
            <button
              key={l.key}
              type="button"
              onClick={() => setLab(l.key)}
              className="relative rounded-md px-3 py-2 text-left transition-colors"
            >
              {lab === l.key && (
                <motion.span
                  layoutId="lab-rail-active"
                  className="absolute inset-0 rounded-md bg-accent/12 shadow-[inset_0_0_0_1px_rgba(129,140,248,0.25)]"
                  transition={{ type: "spring", stiffness: 420, damping: 38 }}
                />
              )}
              <span className={`relative block text-[13px] font-medium ${lab === l.key ? "text-accent" : "text-text-secondary"}`}>{l.label}</span>
              <span className="relative block text-[10px] text-text-tertiary">{l.sub}</span>
            </button>
          ))}
        </div>
      </nav>

      <div className="min-w-0 flex-1">
        <AnimatePresence mode="wait">
          <motion.div
            key={lab}
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.2 }}
          >
            {lab === "deep" && <DeepAnalysisPanel />}
            {lab === "chart" && <ChartStudyPanel />}
            {lab === "fundamentals" && <FundamentalsPanel />}
            {lab === "filings" && <FilingsPanel />}
            {lab === "strategies" && <StrategyLibraryPanel />}
            {lab === "learning" && <LearningPanel />}
            {lab === "trust" && <TrustPanel />}
          </motion.div>
        </AnimatePresence>
      </div>
    </div>
  );
}
