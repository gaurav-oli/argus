"use client";

import {
  ApiError,
  confirmImport,
  listPositions,
  uploadStatement,
  type ImportPreview,
  type ParsedHolding,
  type Position,
} from "@/lib/apiClient";
import { useEffect, useRef, useState } from "react";

function fmtNumber(value: number | null): string {
  return value == null ? "—" : value.toLocaleString(undefined, { maximumFractionDigits: 4 });
}

function fmtMoney(value: number | null, currency: string): string {
  return value == null ? "—" : `${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

/**
 * Portfolio statement import (Story 3.1, FR-1). Upload a brokerage PDF → review a parsed preview
 * (rows that couldn't be fully parsed are flagged, never dropped) → confirm to commit holdings.
 * After confirming, the current holdings are listed so the persisted result is visible. The full
 * sortable holdings table arrives in Story 3.5.
 */
export function ImportStatement() {
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [busy, setBusy] = useState<"idle" | "uploading" | "confirming">("idle");
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let active = true;
    listPositions()
      .then((p) => active && setPositions(p))
      .catch(() => {
        /* leave empty until first successful load */
      });
    return () => {
      active = false;
    };
  }, []);

  function sortByTicker(list: Position[]): Position[] {
    return [...list].sort((a, b) => a.ticker.localeCompare(b.ticker));
  }

  async function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setBusy("uploading");
    try {
      setPreview(await uploadStatement(file));
    } catch (err) {
      setPreview(null);
      setError(err instanceof ApiError ? err.message : "Couldn't read that file");
    } finally {
      setBusy("idle");
      if (fileInput.current) fileInput.current.value = ""; // allow re-selecting the same file
    }
  }

  async function handleConfirm() {
    if (!preview) return;
    setError(null);
    setBusy("confirming");
    try {
      const created = await confirmImport(preview.importId);
      setPreview(null);
      // The import succeeded — show holdings from the authoritative confirm response. A full
      // re-list is best-effort; if it fails we keep the returned rows and never surface an error
      // (which would misleadingly imply the import failed).
      try {
        setPositions(await listPositions());
      } catch {
        setPositions((prev) => sortByTicker([...prev, ...created]));
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't import those holdings");
    } finally {
      setBusy("idle");
    }
  }

  const flaggedCount = preview?.holdings.filter((h) => h.needsReview).length ?? 0;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-medium text-text-primary">Import statement</h3>
          <p className="text-xs text-text-secondary">Upload a brokerage PDF to load your holdings.</p>
        </div>
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

      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}

      {preview && (
        <div className="flex flex-col gap-3 rounded-lg border border-border bg-background p-4">
          <div className="flex items-center justify-between">
            <span className="text-sm text-text-primary">
              {preview.holdings.length} holding{preview.holdings.length === 1 ? "" : "s"} found in{" "}
              <span className="text-text-secondary">{preview.filename}</span>
            </span>
            {flaggedCount > 0 && (
              <span className="text-xs text-warning">{flaggedCount} need review</span>
            )}
          </div>

          {preview.message && <p className="text-sm text-text-secondary">{preview.message}</p>}

          {preview.holdings.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm tabular-nums">
                <thead>
                  <tr className="text-xs uppercase tracking-wide text-text-secondary">
                    <th className="py-1 pr-4 font-medium">Ticker</th>
                    <th className="py-1 pr-4 font-medium">Shares</th>
                    <th className="py-1 pr-4 font-medium">Cost basis</th>
                    <th className="py-1 pr-4 font-medium">Acquired</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.holdings.map((h: ParsedHolding, i) => (
                    <tr
                      key={`${h.ticker}-${i}`}
                      className={h.needsReview ? "border-l-2 border-warning" : undefined}
                    >
                      <td className="py-1 pr-4 font-medium text-text-primary">
                        {h.ticker}
                        {h.needsReview && (
                          <span className="ml-2 text-[11px] text-warning" title={h.issues.join("; ")}>
                            review
                          </span>
                        )}
                      </td>
                      <td className="py-1 pr-4 text-text-primary">{fmtNumber(h.shares)}</td>
                      <td className="py-1 pr-4 text-text-primary">{fmtMoney(h.costBasis, h.costBasisCurrency)}</td>
                      <td className="py-1 pr-4 text-text-secondary">{h.acquisitionDate ?? "—"}</td>
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

      {positions.length > 0 && (
        <div className="flex flex-col gap-2">
          <h4 className="text-xs uppercase tracking-wide text-text-secondary">Current holdings</h4>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm tabular-nums">
              <thead>
                <tr className="text-xs uppercase tracking-wide text-text-secondary">
                  <th className="py-1 pr-4 font-medium">Ticker</th>
                  <th className="py-1 pr-4 font-medium">Shares</th>
                  <th className="py-1 pr-4 font-medium">Cost basis</th>
                  <th className="py-1 pr-4 font-medium">Acquired</th>
                </tr>
              </thead>
              <tbody>
                {positions.map((p) => (
                  <tr key={p.id} className={p.needsReview ? "border-l-2 border-warning" : undefined}>
                    <td className="py-1 pr-4 font-medium text-text-primary">
                      {p.ticker}
                      {p.needsReview && <span className="ml-2 text-[11px] text-warning">review</span>}
                    </td>
                    <td className="py-1 pr-4 text-text-primary">{fmtNumber(p.shares)}</td>
                    <td className="py-1 pr-4 text-text-primary">{fmtMoney(p.costBasis, p.costBasisCurrency)}</td>
                    <td className="py-1 pr-4 text-text-secondary">{p.acquisitionDate ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
