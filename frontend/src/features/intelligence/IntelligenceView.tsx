"use client";

import {
  getInsiderActivity,
  getNewsFeed,
  getSocialSentiment,
  getSourceCredibility,
  getStrangerAlerts,
  type InsiderActivity,
  type NewsItem,
  type SentimentLabel,
  type SourceCredibilityItem,
  type StrangerAlertItem,
  type TickerSentiment,
} from "@/lib/apiClient";
import { RecommendationCards } from "@/features/recommendations/RecommendationCards";
import { PageHeader } from "@/components/ui/PageHeader";
import { Skeleton } from "@/components/ui/Skeleton";
import { useEffect, useState } from "react";

/**
 * Intelligence view (Epic 4 — Agent 1). Surfaces the news feed with sentiment/relevance (Stories
 * 4.1/4.2), the Source Credibility Engine (Story 4.3), and active Stranger Danger alerts (Story 4.4)
 * from the session-gated /api/intelligence endpoints. Read-only; data is produced by the backend
 * agents (or the dev seeder when there's no live Finnhub key).
 */
export function IntelligenceView() {
  const [news, setNews] = useState<NewsItem[] | null>(null);
  const [sources, setSources] = useState<SourceCredibilityItem[] | null>(null);
  const [strangers, setStrangers] = useState<StrangerAlertItem[] | null>(null);
  const [social, setSocial] = useState<TickerSentiment[] | null>(null);
  const [insider, setInsider] = useState<InsiderActivity[] | null>(null);

  useEffect(() => {
    let active = true;
    const load = <T,>(fn: () => Promise<T>, set: (v: T) => void) =>
      fn()
        .then((v) => active && set(v))
        .catch(() => active && set([] as unknown as T));
    load(getNewsFeed, setNews);
    load(getSourceCredibility, setSources);
    load(getStrangerAlerts, setStrangers);
    load(getSocialSentiment, setSocial);
    load(getInsiderActivity, setInsider);
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        eyebrow="Agents 1 & 2"
        title="Intelligence"
        subtitle="News, sentiment, source trust, social chatter, and pump-and-dump watch."
      />

      <div className="flex flex-col gap-6">
      <RecommendationCards />
      {strangers && strangers.length > 0 && <StrangerSection alerts={strangers} />}
      <SocialSection items={social} />
      <InsiderSection items={insider} />
      <NewsSection items={news} />
      <SourceSection items={sources} />
      </div>
    </div>
  );
}

function SocialSection({ items }: { items: TickerSentiment[] | null }) {
  if (items === null) {
    return (
      <Card title="Social sentiment · Agent 2">
        <Skeleton className="h-24 w-full" />
      </Card>
    );
  }
  if (items.length === 0) {
    return (
      <Card title="Social sentiment · Agent 2">
        <p className="text-sm text-text-secondary">
          No social posts yet — Agent 2 tracks StockTwits chatter on your holdings (gathers every ~10 min).
        </p>
      </Card>
    );
  }
  const moodColor = (m: string) =>
    m === "Bullish" ? "var(--color-gains)" : m === "Bearish" ? "var(--color-losses)" : "var(--color-text-secondary)";
  return (
    <Card title="Social sentiment · Agent 2" count={items.length}>
      <ul className="flex flex-col gap-3">
        {items.map((t) => {
          const scored = t.bullish + t.bearish;
          const bullPct = scored === 0 ? 50 : Math.round((t.bullish / scored) * 100);
          return (
            <li key={t.ticker} className="flex items-center gap-3">
              <span className="w-14 shrink-0 font-mono text-sm font-semibold text-text-primary">{t.ticker}</span>
              <div className="flex h-2 flex-1 overflow-hidden rounded-full bg-[var(--hairline)]">
                <div className="h-full bg-gains" style={{ width: `${bullPct}%` }} />
                <div className="h-full bg-losses" style={{ width: `${100 - bullPct}%` }} />
              </div>
              <span className="w-28 shrink-0 text-right font-mono text-[11px] text-text-secondary">
                {t.bullish}▲ {t.bearish}▼ · {t.total}
              </span>
              <span className="w-16 shrink-0 text-right text-xs font-semibold" style={{ color: moodColor(t.mood) }}>
                {t.mood}
              </span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

function InsiderSection({ items }: { items: InsiderActivity[] | null }) {
  if (items === null) {
    return (
      <Card title="Insider activity · Agent 4">
        <Skeleton className="h-20 w-full" />
      </Card>
    );
  }
  if (items.length === 0) {
    return (
      <Card title="Insider activity · Agent 4">
        <p className="text-sm text-text-secondary">
          No recent insider filings — Agent 4 watches SEC EDGAR Form 4s on your holdings (refreshes every ~6h).
        </p>
      </Card>
    );
  }
  const tone = (t: string) =>
    t === "BUY" ? "var(--color-gains)" : t === "SELL" ? "var(--color-losses)" : "var(--color-text-secondary)";
  const fmtShares = (n: number | null) => (n == null ? "" : `${Math.round(n).toLocaleString()} sh`);
  return (
    <Card title="Insider activity · Agent 4" count={items.length}>
      <ul className="flex flex-col divide-y divide-border/50">
        {items.slice(0, 12).map((x, i) => (
          <li key={i} className="flex items-center gap-3 py-2 text-sm">
            <span className="w-12 shrink-0 font-mono font-semibold text-text-primary">{x.ticker}</span>
            <span
              className="w-14 shrink-0 text-xs font-semibold uppercase"
              style={{ color: tone(x.transactionType) }}
            >
              {x.transactionType}
            </span>
            <span className="min-w-0 flex-1 truncate text-text-secondary">
              <span className="text-text-primary">{x.insiderName ?? "—"}</span>
              {x.insiderTitle && <span className="ml-1.5 text-xs">· {x.insiderTitle}</span>}
            </span>
            <span className="shrink-0 font-mono text-[11px] text-text-secondary">{fmtShares(x.shares)}</span>
            <span className="w-20 shrink-0 text-right font-mono text-[11px] text-text-secondary">{x.filedAt}</span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Card({ title, count, children }: { title: string; count?: number; children: React.ReactNode }) {
  return (
    <section className="glass relative overflow-hidden rounded-2xl p-6">
      <h2 className="mb-4 flex items-center gap-2 text-[11px] font-medium uppercase tracking-wide text-text-secondary">
        {title}
        {count != null && (
          <span className="rounded-full bg-border/60 px-1.5 py-0.5 text-[10px] tabular-nums text-text-secondary">
            {count}
          </span>
        )}
      </h2>
      {children}
    </section>
  );
}

function NewsSection({ items }: { items: NewsItem[] | null }) {
  if (items === null) {
    return (
      <Card title="News & Signals">
        <div className="space-y-3">
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-2/3" />
        </div>
      </Card>
    );
  }
  return (
    <Card title="News & Signals" count={items.length}>
      {items.length === 0 ? (
        <Empty>No articles ingested yet. Agent 1 populates this on its next cycle.</Empty>
      ) : (
        <ul className="divide-y divide-border">
          {items.map((n) => (
            <li key={n.id} className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
              <div className="flex items-start justify-between gap-3">
                <p className="text-sm font-medium text-text-primary">{n.headline}</p>
                <SentimentBadge label={n.sentimentLabel} score={n.sentimentScore} />
              </div>
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-text-secondary">
                <span>{n.source}</span>
                <span aria-hidden>·</span>
                <span>{timeAgo(n.publishedAt)}</span>
                {n.tickers.map((t) => (
                  <span
                    key={t}
                    className="rounded bg-border/60 px-1.5 py-0.5 text-[11px] font-medium text-text-primary"
                  >
                    {t}
                  </span>
                ))}
                {n.relevanceScore != null && (
                  <span className="text-[11px]">{Math.round(n.relevanceScore * 100)}% relevant</span>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function SourceSection({ items }: { items: SourceCredibilityItem[] | null }) {
  if (items === null) {
    return (
      <Card title="Source Credibility">
        <Skeleton className="h-4 w-full" />
      </Card>
    );
  }
  return (
    <Card title="Source Credibility" count={items.length}>
      {items.length === 0 ? (
        <Empty>No sources scored yet.</Empty>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {items.map((s) => (
            <li key={s.source} className="flex items-center gap-3">
              <span className="w-40 truncate text-sm text-text-primary">{s.source}</span>
              <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-border/60">
                <div
                  className={`h-full rounded-full ${tier(s.tier).bar}`}
                  style={{ width: `${s.score}%` }}
                />
              </div>
              <span className="w-8 text-right text-xs tabular-nums text-text-secondary">{s.score}</span>
              <span className={`w-20 text-right text-[11px] font-medium ${tier(s.tier).text}`}>
                {s.blocked ? "BLOCKED" : s.tier}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function StrangerSection({ alerts }: { alerts: StrangerAlertItem[] }) {
  return (
    <Card title="Stranger Danger — pump & dump watch" count={alerts.length}>
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {alerts.map((a) => (
          <li key={a.ticker} className="rounded-lg border border-border p-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-semibold text-text-primary">{a.ticker}</span>
              <span className={`text-sm font-bold tabular-nums ${riskColor(a.riskScore)}`}>
                {a.riskScore}
                <span className="ml-0.5 text-[11px] font-normal text-text-secondary">/100 risk</span>
              </span>
            </div>
            <p className="mt-1 text-xs text-text-secondary">
              {a.coverageCount} articles · needs {a.requiredConsensus}/7 agents to recommend
            </p>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function SentimentBadge({ label, score }: { label: SentimentLabel | null; score: number | null }) {
  if (!label) {
    return <span className="shrink-0 text-[11px] text-text-secondary">unscored</span>;
  }
  const styles: Record<SentimentLabel, string> = {
    BULLISH: "bg-gains/15 text-gains",
    BEARISH: "bg-losses/15 text-losses",
    NEUTRAL: "bg-border/60 text-text-secondary",
  };
  return (
    <span
      className={`shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium tabular-nums ${styles[label]}`}
    >
      {label.toLowerCase()}
      {score != null && <span className="ml-1 opacity-80">{score > 0 ? "+" : ""}{score.toFixed(2)}</span>}
    </span>
  );
}

function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-sm text-text-secondary">{children}</p>;
}

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

function riskColor(score: number): string {
  if (score >= 70) return "text-losses";
  if (score >= 40) return "text-warning";
  return "text-text-primary";
}

function timeAgo(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
}
