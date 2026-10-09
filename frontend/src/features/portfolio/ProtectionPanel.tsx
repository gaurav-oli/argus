"use client";

import { CompanyIcon } from "@/components/ui/CompanyIcon";
import { Sensitive } from "@/features/privacy/Sensitive";
import { confirmBrokerStop, decideGuardAlert, getGuard, setGuardEnabled, type GuardAlert, type GuardView } from "@/lib/apiClient";
import { useCompanyLogos } from "@/lib/useCompanyLogos";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { cn } from "@/lib/utils";
import { useEffect, useMemo, useState } from "react";

const KIND_LABEL: Record<GuardAlert["kind"], string> = {
  STOP_BROKEN: "Stop broken",
  STOP_RAISED: "Stop can move up",
  CALL_REVERSED: "Agent 5 turned against it",
  THESIS_AT_RISK: "Thesis at risk",
};

function money(n: number | null | undefined): string {
  return n == null ? "—" : `$${n.toFixed(2)}`;
}

function countdown(ms: number): string {
  if (ms <= 0) return "deciding now…";
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/**
 * Protection for your real holdings. Argus can't place orders at your broker, so it does the next best thing on two
 * fronts: a recommended protective stop per holding to set once as a standing stop order at the broker (which then
 * sells on its own, 24/7), and alerts — answer within 15 minutes, or Argus makes the call itself, records it, and
 * escalates by push and email. Past decisions are scored a week later.
 */
export function ProtectionPanel() {
  const [view, setView] = useState<GuardView | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const load = () =>
    getGuard()
      .then(setView)
      .catch(() => {});
  useAutoRefresh(load, REFRESH.FAST);

  // The countdown ticks every second while anything is waiting on you.
  const waiting = (view?.open.length ?? 0) > 0;
  useEffect(() => {
    if (!waiting) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [waiting]);

  const tickers = useMemo(() => [...new Set((view?.holdings ?? []).map((h) => h.ticker))], [view]);
  const logos = useCompanyLogos(tickers);

  if (!view) return null;

  async function decide(a: GuardAlert, decision: "SELL" | "TIGHTEN" | "HOLD") {
    setBusy(`a${a.id}`);
    try {
      await decideGuardAlert(a.id, decision);
    } catch {
      // Already decided (by Argus at the deadline) — the refresh below shows it.
    }
    await load();
    setBusy(null);
  }

  async function confirm(ticker: string, account: string) {
    setBusy(`s${ticker}${account}`);
    try {
      await confirmBrokerStop(ticker, account);
    } catch {
      // ignore — the refresh shows the current state
    }
    await load();
    setBusy(null);
  }

  async function toggle(on: boolean) {
    setBusy("toggle");
    try {
      await setGuardEnabled(on);
    } catch {
      // the refresh shows the real state
    }
    await load();
    setBusy(null);
  }

  const toUpdate = view.holdings.filter((h) => h.needsBrokerUpdate);
  const toggleButton = (
    <button
      type="button"
      disabled={busy === "toggle"}
      onClick={() => void toggle(!view.enabled)}
      className={cn(
        "shrink-0 border px-2.5 py-1 font-mono text-[11px] transition-colors disabled:opacity-50",
        view.enabled
          ? "border-[var(--glass-border)] text-text-secondary hover:border-losses hover:text-losses"
          : "border-accent bg-accent font-semibold text-background",
      )}
    >
      {view.enabled ? "Turn off" : "Turn on protection"}
    </button>
  );

  if (!view.enabled) {
    return (
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="font-display text-base font-semibold text-text-primary">🛡 Protection is off</h3>
          <p className="mt-0.5 max-w-2xl text-xs text-text-secondary">
            Turn it on and Argus watches your real holdings 4 AM–8 PM ET: a protective stop for each one to set at your broker, alerts when
            something needs action, and — if you don&apos;t answer within 15 minutes — Argus decides, records it, and emails you what to do.
          </p>
        </div>
        {toggleButton}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-start justify-between gap-3">
        <div>
        <h3 className="font-display text-base font-semibold text-text-primary">🛡 Protection</h3>
        <p className="mt-0.5 text-xs text-text-secondary">
          Argus watches your real holdings 4 AM–8 PM ET. Set each stop below as a standing stop order at your broker — it sells on its own
          even if you&apos;re busy. When something needs action you&apos;ll get an alert; if you don&apos;t answer in 15 minutes, Argus decides,
          records it, and emails you what to do.
        </p>
        </div>
        {toggleButton}
      </div>

      {/* Waiting on you */}
      {view.open.length > 0 && (
        <div className="flex flex-col gap-2">
          {view.open.map((a) => {
            const left = new Date(a.decideAt).getTime() - now;
            return (
              <div
                key={a.id}
                className={cn(
                  "flex flex-col gap-2 border p-3 sm:flex-row sm:items-center sm:justify-between",
                  a.recommendation === "SELL" ? "border-losses/50 bg-losses/[0.07]" : "border-warning/50 bg-warning/[0.07]",
                )}
              >
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-text-primary">
                    {a.recommendation === "SELL" ? "⚠ Consider selling" : "🛡 Tighten your stop on"} {a.ticker}
                    {a.account && <span className="font-normal text-text-secondary"> · {a.account}</span>}
                    <span className="ml-2 font-normal text-text-tertiary">{KIND_LABEL[a.kind]}</span>
                  </p>
                  <p className="mt-0.5 text-[11px] text-text-secondary">{a.detail}</p>
                  <p className="mt-1 font-mono text-[11px] text-warning">Argus decides in {countdown(left)}</p>
                </div>
                <div className="flex shrink-0 flex-wrap gap-1.5 font-mono text-[11px]">
                  {(["SELL", "TIGHTEN", "HOLD"] as const).map((d) => (
                    <button
                      key={d}
                      type="button"
                      disabled={busy === `a${a.id}`}
                      onClick={() => void decide(a, d)}
                      className={cn(
                        "border px-2.5 py-1 transition-colors disabled:opacity-50",
                        d === a.recommendation
                          ? "border-accent bg-accent font-semibold text-background"
                          : "border-[var(--glass-border)] text-text-secondary hover:border-accent hover:text-accent",
                      )}
                    >
                      {d === "SELL" ? "I sold" : d === "TIGHTEN" ? "I moved my stop" : "Keep holding"}
                    </button>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Stops to set at the broker */}
      {view.holdings.length === 0 ? (
        <p className="text-xs text-text-secondary">Protection starts on the next check (every 5 minutes, 4 AM–8 PM ET).</p>
      ) : (
        <div>
          <p className="mb-1.5 text-[10px] font-medium uppercase tracking-wider text-text-secondary">
            Stops to keep at your broker
            {toUpdate.length > 0 && <span className="ml-2 normal-case text-warning">{toUpdate.length} to set or update</span>}
          </p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[34rem] text-left font-mono text-[11px] tabular-nums">
              <thead className="border-b border-[var(--hairline)] text-[10px] uppercase tracking-wider text-text-secondary">
                <tr>
                  <th className="py-1.5 font-normal">Holding</th>
                  <th className="py-1.5 text-right font-normal">Price</th>
                  <th className="py-1.5 text-right font-normal">Stop</th>
                  <th className="py-1.5 text-right font-normal">Below</th>
                  <th className="py-1.5 pl-4 font-normal">At your broker</th>
                </tr>
              </thead>
              <tbody>
                {[...view.holdings]
                  .sort((x, y) => Number(y.needsBrokerUpdate) - Number(x.needsBrokerUpdate) || x.ticker.localeCompare(y.ticker))
                  .map((h) => (
                    <tr key={`${h.ticker}|${h.account}`} className="border-b border-[var(--hairline)] last:border-b-0">
                      <td className="py-1.5">
                        <span className="flex items-center gap-2">
                          <CompanyIcon ticker={h.ticker} logoUrl={logos[h.ticker]} title={h.ticker} size={18} />
                          <span className="font-semibold text-text-primary">{h.ticker}</span>
                          {h.account && <span className="truncate text-text-tertiary">{h.account}</span>}
                        </span>
                      </td>
                      <td className="py-1.5 text-right text-text-secondary">
                        <Sensitive>{money(h.price)}</Sensitive>
                      </td>
                      <td className="py-1.5 text-right font-semibold text-text-primary">
                        <Sensitive>{money(h.stop)}</Sensitive>
                      </td>
                      <td className="py-1.5 text-right text-text-tertiary">
                        {h.price ? `${(((h.price - h.stop) / h.price) * 100).toFixed(1)}%` : "—"}
                      </td>
                      <td className="py-1.5 pl-4">
                        {h.needsBrokerUpdate ? (
                          <button
                            type="button"
                            disabled={busy === `s${h.ticker}${h.account}`}
                            onClick={() => void confirm(h.ticker, h.account)}
                            className="border border-warning/50 px-2 py-0.5 text-warning transition-colors hover:bg-warning hover:text-background disabled:opacity-50"
                          >
                            {h.brokerStop == null ? "I've set it" : <>I&apos;ve updated it</>}
                          </button>
                        ) : (
                          <span className="text-gains">✓ set</span>
                        )}
                        {h.needsBrokerUpdate && h.brokerStop != null && (
                          <span className="ml-2 text-text-tertiary">
                            was <Sensitive>{money(h.brokerStop)}</Sensitive>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Decisions and how they turned out */}
      {view.history.length > 0 && (
        <div>
          <p className="mb-1.5 text-[10px] font-medium uppercase tracking-wider text-text-secondary">Decisions · last 30 days</p>
          <ul className="flex flex-col gap-1 font-mono text-[11px] text-text-secondary">
            {view.history.map((a) => (
              <li key={a.id} className="flex flex-wrap items-baseline gap-x-2">
                <span className="text-text-primary">{a.ticker}</span>
                <span>{a.decision}</span>
                <span className={a.decidedBy === "ARGUS" ? "text-accent" : ""}>{a.decidedBy === "ARGUS" ? "· decided by Argus" : "· your call"}</span>
                <span className="text-text-tertiary">{a.decidedAt ? new Date(a.decidedAt).toLocaleString() : ""}</span>
                {a.outcomePct != null && (
                  <span className={a.decision === "SELL" ? (a.outcomePct < 0 ? "text-gains" : "text-losses") : "text-text-secondary"}>
                    · price {a.outcomePct >= 0 ? "+" : ""}
                    {a.outcomePct.toFixed(1)}% a week later{a.decision === "SELL" ? (a.outcomePct < 0 ? " (selling helped)" : " (selling cost)") : ""}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
