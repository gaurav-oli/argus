"use client";

/** A compact, click-to-cycle sort control — no dropdown chrome, matches the app's rule-based look. */
export function SortToggle<T extends string>({
  options,
  value,
  onChange,
}: {
  options: readonly { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  const idx = options.findIndex((o) => o.value === value);
  const current = options[idx] ?? options[0];

  function cycle() {
    onChange(options[(idx + 1) % options.length].value);
  }

  return (
    <button
      type="button"
      onClick={cycle}
      className="flex items-center gap-1 text-[11px] font-medium text-text-secondary transition-colors hover:text-text-primary"
      aria-label={`Sort: ${current.label}. Click to change.`}
    >
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M3 7h11M3 12h7M3 17h4" />
        <path d="M17 6v13M17 19l3-3M17 19l-3-3" />
      </svg>
      {current.label}
    </button>
  );
}
