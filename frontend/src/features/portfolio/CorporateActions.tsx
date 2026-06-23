"use client";

import {
  ApiError,
  confirmCorporateAction,
  dismissCorporateAction,
  listCorporateActions,
  recordCorporateAction,
  type CorporateAction,
} from "@/lib/apiClient";
import { useEffect, useState } from "react";

const TYPES = [
  { value: "split", label: "Split", needs: "ratio" },
  { value: "reverse_split", label: "Reverse split", needs: "ratio" },
  { value: "ticker_change", label: "Ticker change", needs: "newTicker" },
  { value: "merger", label: "Merger", needs: "both" },
  { value: "stock_dividend", label: "Stock dividend", needs: "none" },
] as const;

function statusClass(status: string): string {
  if (status === "pending") return "text-warning";
  if (status === "applied") return "text-gains";
  return "text-text-secondary";
}

function summarize(a: CorporateAction): string {
  if (a.type === "ticker_change") return `→ ${a.newTicker ?? "?"}`;
  if (a.type === "merger") return `${a.ratio ? `${a.ratio}× ` : ""}${a.newTicker ? `→ ${a.newTicker}` : ""}`.trim() || "merger";
  if (a.ratio != null) {
    // Show a reverse split (ratio < 1) as 1:N rather than the misleading "0.1:1".
    if (a.type === "reverse_split" && a.ratio > 0 && a.ratio < 1) return `1:${Math.round(1 / a.ratio)}`;
    return `${a.ratio}:1`;
  }
  return "";
}

/** Confirming only does something for these types; stock dividends can only be dismissed. */
function canConfirm(type: string): boolean {
  return type !== "stock_dividend";
}

/**
 * Corporate actions (Story 3.3, FR-1c). Record a split / ticker change / merger; unambiguous ones
 * auto-apply, ambiguous ones land as pending (🟡) for Confirm / Dismiss. Applying a split changes
 * share counts while preserving total cost basis, so we reload to refresh the holdings view.
 */
export function CorporateActions() {
  const [actions, setActions] = useState<CorporateAction[]>([]);
  const [ticker, setTicker] = useState("");
  const [type, setType] = useState<string>("split");
  const [ratio, setRatio] = useState("");
  const [newTicker, setNewTicker] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listCorporateActions()
      .then((a) => active && setActions(a))
      .catch(() => {
        /* leave empty */
      });
    return () => {
      active = false;
    };
  }, []);

  function refresh() {
    listCorporateActions().then(setActions).catch(() => setError("Couldn't refresh corporate actions"));
  }

  const needs = TYPES.find((t) => t.value === type)?.needs ?? "none";

  async function handleRecord() {
    if (!ticker.trim()) {
      setError("Enter a ticker");
      return;
    }
    const r = Number(ratio);
    const hasValidRatio = Number.isFinite(r) && r > 0;
    // For a split/reverse split the ratio is mandatory to apply — tell the user up front rather
    // than silently posting a ratio-less action that the backend parks as pending.
    if (needs === "ratio" && !hasValidRatio) {
      setError("Enter a positive split ratio (e.g. 2 for a 2:1 split)");
      return;
    }
    if (needs === "newTicker" && !newTicker.trim()) {
      setError("Enter the new ticker");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const body: { ticker: string; type: string; ratio?: number; newTicker?: string } = {
        ticker: ticker.trim().toUpperCase(),
        type,
      };
      if ((needs === "ratio" || needs === "both") && hasValidRatio) {
        body.ratio = r;
      }
      if (needs === "newTicker" || needs === "both") {
        if (newTicker.trim()) body.newTicker = newTicker.trim().toUpperCase();
      }
      const result = await recordCorporateAction(body);
      setTicker("");
      setRatio("");
      setNewTicker("");
      if (result.status === "applied") {
        window.location.reload(); // shares/cost changed → refresh holdings too
        return;
      }
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't record that action");
    } finally {
      setBusy(false);
    }
  }

  async function handleConfirm(id: number) {
    setError(null);
    try {
      await confirmCorporateAction(id);
      window.location.reload(); // applied → holdings may have changed
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't apply that action");
    }
  }

  async function handleDismiss(id: number) {
    setError(null);
    try {
      await dismissCorporateAction(id);
      refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't dismiss that action");
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h3 className="text-sm font-medium text-text-primary">Corporate actions</h3>
        <p className="text-xs text-text-secondary">
          Apply splits, ticker changes, and mergers. Ambiguous ones wait for your confirmation.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <input
          value={ticker}
          onChange={(e) => setTicker(e.target.value)}
          placeholder="Ticker"
          className="w-28 rounded-lg border border-border bg-background px-2 py-1.5 text-sm text-text-primary uppercase"
        />
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="rounded-lg border border-border bg-background px-2 py-1.5 text-sm text-text-primary"
        >
          {TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
        {(needs === "ratio" || needs === "both") && (
          <input
            type="number"
            step="0.0001"
            min="0"
            value={ratio}
            onChange={(e) => setRatio(e.target.value)}
            placeholder="Ratio (e.g. 2)"
            className="w-32 rounded-lg border border-border bg-background px-2 py-1.5 text-sm text-text-primary"
          />
        )}
        {(needs === "newTicker" || needs === "both") && (
          <input
            value={newTicker}
            onChange={(e) => setNewTicker(e.target.value)}
            placeholder="New ticker"
            className="w-28 rounded-lg border border-border bg-background px-2 py-1.5 text-sm text-text-primary uppercase"
          />
        )}
        <button
          onClick={handleRecord}
          disabled={busy}
          className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-background transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Recording…" : "Record"}
        </button>
      </div>

      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}

      {actions.length > 0 && (
        <ul className="flex flex-col gap-2">
          {actions.map((a) => (
            <li
              key={a.id}
              className={`flex items-center justify-between rounded-lg border bg-background px-3 py-2 ${a.status === "pending" ? "border-warning" : "border-border"}`}
            >
              <span className="text-sm text-text-primary">
                <span className="font-medium">{a.ticker}</span>{" "}
                <span className="text-text-secondary">{a.type.replaceAll("_", " ")}</span>{" "}
                <span className="text-text-secondary">{summarize(a)}</span>
                <span className={`ml-2 text-xs ${statusClass(a.status)}`}>
                  {a.status === "pending" ? "🟡 pending" : a.status}
                </span>
                {a.status === "pending" && a.note && (
                  <span className="ml-2 text-xs text-text-secondary">— {a.note}</span>
                )}
              </span>
              {a.status === "pending" && (
                <span className="flex items-center gap-3">
                  {canConfirm(a.type) && (
                    <button
                      onClick={() => handleConfirm(a.id)}
                      className="text-xs font-medium text-accent hover:underline"
                    >
                      Confirm
                    </button>
                  )}
                  <button
                    onClick={() => handleDismiss(a.id)}
                    className="text-xs text-text-secondary transition-colors hover:text-losses"
                  >
                    Dismiss
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
