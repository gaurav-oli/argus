"use client";

import {
  ApiError,
  confirmImport,
  uploadStatement,
  type ImportPreview,
  type ParsedHolding,
} from "@/lib/apiClient";
import { useRef, useState } from "react";

function fmtNumber(value: number | null): string {
  return value == null ? "—" : value.toLocaleString(undefined, { maximumFractionDigits: 4 });
}

function fmtMoney(value: number | null, currency: string): string {
  return value == null
    ? "—"
    : `${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

const BANKS = ["National Bank", "RBC", "TD", "Scotiabank", "BMO", "CIBC", "Wealthsimple", "Questrade", "Other"];

/**
 * Statement import (Story 3.1 + Multi-Bank Holdings). Pick the bank → upload its PDF → review the
 * AI-parsed preview (bank + account captured; flagged rows kept, never dropped) → confirm. The
 * confirmed holdings appear in the single Holdings table below (which reconciles, no duplicates).
 */
export function ImportStatement() {
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [bank, setBank] = useState<string>(BANKS[0]);
  const [busy, setBusy] = useState<"idle" | "uploading" | "confirming">("idle");
  const [error, setError] = useState<string | null>(null);
  const [savedCount, setSavedCount] = useState<number | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  async function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setSavedCount(null);
    setBusy("uploading");
    try {
      setPreview(await uploadStatement(file, { mode: "llm", institution: bank }));
    } catch (err) {
      setPreview(null);
      setError(err instanceof ApiError ? err.message : "Couldn't read that file");
    } finally {
      setBusy("idle");
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function handleConfirm() {
    if (!preview) return;
    setError(null);
    setBusy("confirming");
    try {
      const created = await confirmImport(preview.importId);
      setSavedCount(created.length);
      setPreview(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't import those holdings");
    } finally {
      setBusy("idle");
    }
  }

  const flaggedCount = preview?.holdings.filter((h) => h.needsReview).length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-medium text-text-primary">Import statement</h3>
          <p className="text-xs text-text-secondary">Pick the bank, then upload its PDF — parsed with AI.</p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={bank}
            onChange={(e) => setBank(e.target.value)}
            disabled={busy !== "idle"}
            aria-label="Bank"
            className="cursor-pointer rounded-lg border border-border bg-background px-2.5 py-2 text-xs font-medium text-text-primary transition-colors hover:border-accent focus:border-accent focus:outline-none"
          >
            {BANKS.map((b) => (
              <option key={b} value={b}>
                {b}
              </option>
            ))}
          </select>
          <label className="cursor-pointer rounded-lg border border-border bg-background px-3 py-2 text-xs font-medium text-accent transition-colors hover:border-accent">
            {busy === "uploading" ? "Reading…" : "Choose PDF"}
            <input
              ref={fileInput}
              type="file"
              accept="application/pdf,.pdf"
              className="sr-only"
              disabled={busy !== "idle"}
              onChange={handleFile}
            />
          </label>
        </div>
      </div>

      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}

      {savedCount != null && !preview && (
        <p className="text-sm text-gains">
          Imported {savedCount} holding{savedCount === 1 ? "" : "s"} from {bank} — see the Holdings table below.
        </p>
      )}

      {preview && (
        <div className="flex flex-col gap-3 rounded-lg border border-border bg-background p-4">
          <div className="flex items-center justify-between">
            <span className="text-sm text-text-primary">
              {preview.holdings.length} holding{preview.holdings.length === 1 ? "" : "s"} found in{" "}
              <span className="text-text-secondary">{preview.filename}</span> ({bank})
            </span>
            {flaggedCount > 0 && <span className="text-xs text-warning">{flaggedCount} need review</span>}
          </div>

          {preview.message && <p className="text-sm text-text-secondary">{preview.message}</p>}

          {preview.holdings.length > 0 && (
            <div className="max-h-72 overflow-auto">
              <table className="w-full text-left text-sm tabular-nums">
                <thead>
                  <tr className="text-xs uppercase tracking-wide text-text-secondary">
                    <th className="py-1 pr-4 font-medium">Ticker</th>
                    <th className="py-1 pr-4 font-medium">Account</th>
                    <th className="py-1 pr-4 text-right font-medium">Shares</th>
                    <th className="py-1 pr-4 text-right font-medium">Cost basis</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.holdings.map((h: ParsedHolding, i) => (
                    <tr key={`${h.ticker}-${i}`} className={h.needsReview ? "border-l-2 border-warning" : undefined}>
                      <td className="py-1 pr-4 font-medium text-text-primary">
                        {h.ticker}
                        {h.needsReview && (
                          <span className="ml-2 text-[11px] text-warning" title={h.issues.join("; ")}>
                            review
                          </span>
                        )}
                      </td>
                      <td className="py-1 pr-4 text-text-secondary">{h.account ?? "—"}</td>
                      <td className="py-1 pr-4 text-right text-text-primary">{fmtNumber(h.shares)}</td>
                      <td className="py-1 pr-4 text-right text-text-primary">{fmtMoney(h.costBasis, h.costBasisCurrency)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="flex items-center gap-3">
            <button
              onClick={handleConfirm}
              disabled={busy !== "idle" || preview.holdings.length === 0}
              className="rounded-lg bg-accent px-3 py-2 text-xs font-medium text-background transition-opacity hover:opacity-90 disabled:opacity-50"
            >
              {busy === "confirming" ? "Importing…" : "Confirm import"}
            </button>
            <button
              onClick={() => setPreview(null)}
              className="text-xs font-medium text-text-secondary transition-colors hover:text-text-primary"
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
