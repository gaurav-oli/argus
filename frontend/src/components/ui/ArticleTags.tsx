/** Mirrors the backend's MacroRelevanceTagger.MACRO_TAG — a pseudo-ticker for macro/political
 * articles (tariffs, Fed policy, currency intervention) that name no specific stock, added
 * alongside any real ticker matches in the same tickers[] array. */
const MACRO_TAG = "MACRO";

/**
 * Renders an article's ticker tags — real symbols as the existing accent pill, the macro/political
 * pseudo-tag as a distinctly-styled badge so it never reads as an unknown/broken stock symbol.
 * Shared by Market News and Breaking Alerts, which both display the same NewsArticle-derived
 * tickers[] array (and previously duplicated this rendering — kept in sync manually, the same
 * failure mode that let the two backend macro-keyword lists silently drift apart).
 */
export function ArticleTags({ tickers }: { tickers: string[] }) {
  if (tickers.length === 0) return null;
  const isMacro = tickers.includes(MACRO_TAG);
  const real = tickers.filter((t) => t !== MACRO_TAG);

  return (
    <div className="mt-3 flex flex-wrap gap-1.5">
      {isMacro && (
        <span className="inline-flex items-center gap-1 rounded-md bg-warning/[0.1] px-1.5 py-0.5 text-[10px] font-medium text-warning">
          <svg viewBox="0 0 16 16" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden>
            <circle cx="8" cy="8" r="6.5" />
            <path d="M1.5 8h13M8 1.5c2 2 2 11 0 13M8 1.5c-2 2-2 11 0 13" />
          </svg>
          Macro / political
        </span>
      )}
      {real.map((t) => (
        <span
          key={t}
          className="rounded-md bg-accent/[0.08] px-1.5 py-0.5 font-mono text-[10px] font-medium text-accent"
        >
          {t}
        </span>
      ))}
    </div>
  );
}
