"use client";

import {
  ApiError,
  confirmImport,
  listPendingImports,
  uploadStatementAuto,
  type ImportPreview,
  type ParsedHolding,
} from "@/lib/apiClient";
import { useEffect, useRef, useState } from "react";

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
 * Statement import (Story 3.1 + "never fails" automatic import). Pick the bank → upload its PDF →
 * it's handed to the background runner, which tries local Gemma with a self-verification loop
 * (Claude only as a last resort) and either applies it to the portfolio automatically or leaves it
 * here, staged, for a quick manual look — either way you're notified (push + email) once it's done,
 * since a real statement can take a little while to read carefully.
 */
export function ImportStatement() {
  const [bank, setBank] = useState<string>(BANKS[0]);
  const [busy, setBusy] = useState<"idle" | "uploading" | "confirming">("idle");
  const [error, setError] = useState<string | null>(null);
  const [processingNote, setProcessingNote] = useState<string | null>(null);
  const [pending, setPending] = useState<ImportPreview[]>([]);
  const [confirmedCount, setConfirmedCount] = useState<number | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let active = true;
    listPendingImports()
      .then((p) => active && setPending(p))
      .catch(() => {
        // Best-effort — a failed load just means the "needs review" list starts empty.
      });
    return () => {
      active = false;
    };
  }, []);

  async function handleFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setConfirmedCount(null);
    setBusy("uploading");
    try {
      const accepted = await uploadStatementAuto(file, bank);
      setProcessingNote(accepted.message);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't read that file");
    } finally {
      setBusy("idle");
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  async function handleConfirm(preview: ImportPreview) {
    setError(null);
    setBusy("confirming");
    try {
      const created = await confirmImport(preview.importId);
      setConfirmedCount(created.length);
      setPending((p) => p.filter((x) => x.importId !== preview.importId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't import those holdings");
    } finally {
      setBusy("idle");
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h3 className="text-sm font-medium text-text-primary">Import statement</h3>
          <p className="text-xs text-text-secondary">
            Pick the bank, then upload its PDF — read automatically, applied once it&apos;s sure.
          </p>
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
            {busy === "uploading" ? "Uploading…" : "Choose PDF"}
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

      {processingNote && (
        <p className="rounded-lg border border-border bg-background p-3 text-sm text-text-secondary">
          {processingNote}
        </p>
      )}

      {confirmedCount != null && (
        <p className="text-sm text-gains">
          Imported {confirmedCount} holding{confirmedCount === 1 ? "" : "s"} — see the Holdings table below.
        </p>
      )}

      {pending.length > 0 && (
        <div className="flex flex-col gap-3">
          <p className="text-xs font-medium uppercase tracking-wide text-warning">
            {pending.length} statement{pending.length === 1 ? "" : "s"} need{pending.length === 1 ? "s" : ""} a quick look
          </p>
          {pending.map((preview) => (
            <PendingImport key={preview.importId} preview={preview} busy={busy} onConfirm={() => handleConfirm(preview)} />
          ))}
        </div>
      )}
    </div>
  );
}

function PendingImport({
  preview,
  busy,
  onConfirm,
}: {
  preview: ImportPreview;
  busy: "idle" | "uploading" | "confirming";
  onConfirm: () => void;
}) {
  const flaggedCount = preview.holdings.filter((h) => h.needsReview).length;
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border bg-background p-4">
      <div className="flex items-center justify-between">
        <span className="text-sm text-text-primary">
          {preview.holdings.length} holding{preview.holdings.length === 1 ? "" : "s"} found in{" "}
          <span className="text-text-secondary">{preview.filename}</span>
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
          onClick={onConfirm}
          disabled={busy !== "idle" || preview.holdings.length === 0}
          className="rounded-lg bg-accent px-3 py-2 text-xs font-medium text-background transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {busy === "confirming" ? "Importing…" : "Looks right — confirm"}
        </button>
      </div>
    </div>
  );
}
