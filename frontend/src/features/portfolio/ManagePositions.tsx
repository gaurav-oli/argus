"use client";

import {
  ApiError,
  addPosition,
  editPosition,
  listAudit,
  listPositions,
  removePosition,
  type AuditEntry,
  type Position,
} from "@/lib/apiClient";
import { useEffect, useState } from "react";

const input = "rounded-lg border border-border bg-background px-2 py-1.5 text-sm text-text-primary";

/**
 * Manual position add / edit / remove (Story 3.7, FR-5). Add a holding by hand, edit shares/cost
 * inline, or remove it; every change is audited (shown below) and the backend re-pushes the live
 * snapshot so the value summary + holdings table update immediately. Edits to a multi-lot position's
 * shares/cost are rejected server-side with a clear message.
 */
export function ManagePositions() {
  const [positions, setPositions] = useState<Position[]>([]);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // add form
  const [ticker, setTicker] = useState("");
  const [shares, setShares] = useState("");
  const [costBasis, setCostBasis] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [acquisitionDate, setAcquisitionDate] = useState("");

  // inline edit
  const [editId, setEditId] = useState<number | null>(null);
  const [editShares, setEditShares] = useState("");
  const [editCost, setEditCost] = useState("");

  function refresh() {
    listPositions().then(setPositions).catch(() => {});
    listAudit().then(setAudit).catch(() => {});
  }

  useEffect(() => {
    refresh();
  }, []);

  async function run(action: () => Promise<unknown>, fail: string) {
    setError(null);
    setBusy(true);
    try {
      await action();
      refresh();
      return true;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : fail);
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function handleAdd() {
    const sh = Number(shares);
    const cb = Number(costBasis);
    if (!ticker.trim() || !Number.isFinite(sh) || sh <= 0 || !Number.isFinite(cb) || cb < 0) {
      setError("Enter a ticker, positive shares, and a cost basis");
      return;
    }
    const ok = await run(
      () =>
        addPosition({
          ticker: ticker.trim().toUpperCase(),
          shares: sh,
          costBasis: cb,
          currency,
          acquisitionDate: acquisitionDate || undefined,
        }),
      "Couldn't add that position",
    );
    if (ok) {
      setTicker("");
      setShares("");
      setCostBasis("");
      setAcquisitionDate("");
    }
  }

  function startEdit(p: Position) {
    setEditId(p.id);
    setEditShares(p.shares?.toString() ?? "");
    setEditCost(p.costBasis?.toString() ?? "");
  }

  async function saveEdit(id: number) {
    const body: { shares?: number; costBasis?: number } = {};
    const sh = Number(editShares);
    const cb = Number(editCost);
    if (Number.isFinite(sh) && sh > 0) body.shares = sh;
    if (Number.isFinite(cb) && cb >= 0) body.costBasis = cb;
    const ok = await run(() => editPosition(id, body), "Couldn't save that edit");
    if (ok) setEditId(null);
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h3 className="text-sm font-medium text-text-primary">Manage positions</h3>
        <p className="text-xs text-text-secondary">Add, edit, or remove a holding by hand.</p>
      </div>

      <div className="flex flex-wrap items-end gap-2">
        <input value={ticker} onChange={(e) => setTicker(e.target.value)} placeholder="Ticker" className={`${input} w-24 uppercase`} />
        <input value={shares} onChange={(e) => setShares(e.target.value)} placeholder="Shares" type="number" className={`${input} w-24`} />
        <input value={costBasis} onChange={(e) => setCostBasis(e.target.value)} placeholder="Cost basis" type="number" className={`${input} w-28`} />
        <select value={currency} onChange={(e) => setCurrency(e.target.value)} className={input}>
          <option>USD</option>
          <option>CAD</option>
        </select>
        <input value={acquisitionDate} onChange={(e) => setAcquisitionDate(e.target.value)} type="date" className={input} />
        <button
          onClick={handleAdd}
          disabled={busy}
          className="rounded-lg bg-accent px-3 py-1.5 text-xs font-medium text-background transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          Add
        </button>
      </div>

      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}

      {positions.length > 0 && (
        <ul className="flex flex-col gap-2">
          {positions.map((p) => (
            <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-border bg-background px-3 py-2">
              <span className="text-sm text-text-primary">
                <span className="font-medium">{p.ticker}</span>{" "}
                {editId === p.id ? (
                  <span className="inline-flex items-center gap-1">
                    <input value={editShares} onChange={(e) => setEditShares(e.target.value)} type="number" className={`${input} w-20 py-0.5`} />
                    <input value={editCost} onChange={(e) => setEditCost(e.target.value)} type="number" className={`${input} w-24 py-0.5`} />
                  </span>
                ) : (
                  <span className="text-text-secondary">
                    {p.shares ?? "—"} sh · {p.costBasis ?? "—"} {p.costBasisCurrency}
                  </span>
                )}
              </span>
              <span className="flex items-center gap-3 text-xs font-medium">
                {editId === p.id ? (
                  <>
                    <button onClick={() => saveEdit(p.id)} className="text-accent hover:underline">Save</button>
                    <button onClick={() => setEditId(null)} className="text-text-secondary hover:text-text-primary">Cancel</button>
                  </>
                ) : (
                  <>
                    <button onClick={() => startEdit(p)} className="text-text-secondary hover:text-text-primary">Edit</button>
                    <button onClick={() => run(() => removePosition(p.id), "Couldn't remove")} className="text-text-secondary hover:text-losses">Remove</button>
                  </>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}

      {audit.length > 0 && (
        <div className="flex flex-col gap-1">
          <h4 className="text-xs uppercase tracking-wide text-text-secondary">Recent changes</h4>
          <ul className="flex flex-col gap-0.5">
            {audit.slice(0, 5).map((a) => (
              <li key={a.id} className="text-xs text-text-secondary">
                <span className="text-text-primary">{a.action}</span> {a.detail ?? a.ticker}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
