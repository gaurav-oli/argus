import { parseSummary } from "@/lib/parseSummary";

/** Renders a Gemma-written summary (what happened / why it matters / market impact, plus an optional
 * beginner glossary) as separate paragraphs with a "Key terms" box. Shared by every curated-news
 * carousel card. */
export function SummaryBlock({ text }: { text: string }) {
  const { paragraphs, terms } = parseSummary(text);
  return (
    <>
      <div className="mt-2 flex flex-col gap-2">
        {paragraphs.map((p, i) => (
          <p key={i} className="text-sm leading-relaxed text-text-secondary">
            {p}
          </p>
        ))}
      </div>
      {terms.length > 0 && (
        <div className="mt-3 rounded-lg border border-border/50 bg-border/[0.15] p-3">
          <h5 className="text-[10px] font-semibold uppercase tracking-wider text-text-secondary">
            📘 Key terms
          </h5>
          <dl className="mt-1.5 space-y-1">
            {terms.map((t) => (
              <div key={t.term} className="text-xs leading-relaxed">
                <dt className="inline font-semibold text-text-primary">{t.term}</dt>
                {t.def && <dd className="inline text-text-secondary"> — {t.def}</dd>}
              </div>
            ))}
          </dl>
        </div>
      )}
    </>
  );
}
