"use client";

import { useEffect, useState } from "react";

import { getInvestorProfile, putInvestorProfile } from "@/lib/apiClient";

/**
 * User-editable investor profile (Profile → Investor profile, Story 7.6): the things Argus can't infer
 * from statements — risk tolerance, financial goal, target (amount + date), residency / home currency,
 * and free-text preferences. These ground the portfolio chat (FR-31) and the Canadian persona (FR-34);
 * blank fields fall back to the derived/config defaults.
 */
const RISK_OPTIONS = ["CONSERVATIVE", "BALANCED", "GROWTH", "AGGRESSIVE"] as const;

const inputClass =
  "rounded-lg border border-border bg-transparent px-2.5 py-1.5 text-sm text-text-primary outline-none focus:border-accent";

export function InvestorProfileSetting() {
  const [loaded, setLoaded] = useState(false);
  const [riskTolerance, setRiskTolerance] = useState<string>("");
  const [financialGoal, setFinancialGoal] = useState("");
  const [targetAmount, setTargetAmount] = useState("");
  const [targetDate, setTargetDate] = useState("");
  const [residency, setResidency] = useState("");
  const [homeCurrency, setHomeCurrency] = useState("");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getInvestorProfile()
      .then((p) => {
        if (!active) return;
        setRiskTolerance(p.riskTolerance ?? "");
        setFinancialGoal(p.financialGoal ?? "");
        setTargetAmount(p.targetAmount == null ? "" : String(p.targetAmount));
        setTargetDate(p.targetDate ?? "");
        setResidency(p.residency ?? "");
        setHomeCurrency(p.homeCurrency ?? "");
        setNotes(p.notes ?? "");
      })
      .catch(() => active && setError("Couldn't load your profile."))
      .finally(() => active && setLoaded(true));
    return () => {
      active = false;
    };
  }, []);

  async function save() {
    setSaving(true);
    setError(null);
    try {
      await putInvestorProfile({
        riskTolerance: riskTolerance || null,
        financialGoal: financialGoal.trim() || null,
        targetAmount: targetAmount.trim() === "" ? null : Number(targetAmount),
        targetDate: targetDate || null,
        residency: residency.trim() || null,
        homeCurrency: homeCurrency.trim().toUpperCase() || null,
        notes: notes.trim() || null,
      });
      setSavedAt(Date.now());
    } catch {
      setError("Couldn't save your profile.");
    } finally {
      setSaving(false);
    }
  }

  if (!loaded) return <p className="text-xs text-text-secondary">Loading profile…</p>;

  return (
    <div className="flex flex-col gap-4">
      <p className="text-xs text-text-secondary">
        Argus already knows your accounts. Tell it the rest — these guide the portfolio chat and the
        Canadian persona. Anything left blank falls back to sensible defaults.
      </p>

      <Field label="Risk tolerance">
        <select value={riskTolerance} onChange={(e) => setRiskTolerance(e.target.value)} className={inputClass}>
          <option value="">— Not set —</option>
          {RISK_OPTIONS.map((r) => (
            <option key={r} value={r}>
              {r.charAt(0) + r.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </Field>

      <Field label="Financial goal">
        <input
          value={financialGoal}
          onChange={(e) => setFinancialGoal(e.target.value)}
          placeholder="e.g. Retire by 55"
          maxLength={200}
          className={`${inputClass} w-full`}
        />
      </Field>

      <div className="flex flex-wrap gap-4">
        <Field label="Target amount">
          <input
            value={targetAmount}
            onChange={(e) => setTargetAmount(e.target.value)}
            type="number"
            min={0}
            placeholder="2000000"
            className={`${inputClass} w-40`}
          />
        </Field>
        <Field label="Target date">
          <input value={targetDate} onChange={(e) => setTargetDate(e.target.value)} type="date" className={inputClass} />
        </Field>
      </div>

      <div className="flex flex-wrap gap-4">
        <Field label="Residency">
          <input
            value={residency}
            onChange={(e) => setResidency(e.target.value)}
            placeholder="Canadian"
            maxLength={40}
            className={`${inputClass} w-40`}
          />
        </Field>
        <Field label="Home currency">
          <input
            value={homeCurrency}
            onChange={(e) => setHomeCurrency(e.target.value.toUpperCase())}
            placeholder="CAD"
            maxLength={3}
            className={`${inputClass} w-24 uppercase`}
          />
        </Field>
      </div>

      <Field label="Preferences / notes">
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="e.g. Prefers low-turnover, tax-efficient ETFs; avoids leverage."
          maxLength={500}
          rows={3}
          className={`${inputClass} w-full resize-y`}
        />
      </Field>

      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={save}
          disabled={saving}
          className="rounded-lg bg-accent px-3 py-1.5 text-sm font-semibold text-white transition hover:opacity-90 disabled:opacity-50"
        >
          {saving ? "Saving…" : "Save profile"}
        </button>
        {savedAt && !saving && <span className="text-xs text-gains">✓ Saved</span>}
        {error && <span className="text-xs text-losses">{error}</span>}
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-xs font-semibold uppercase tracking-wide text-text-secondary">{label}</span>
      {children}
    </label>
  );
}
