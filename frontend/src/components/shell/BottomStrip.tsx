/**
 * Bottom strip — agent status + RAM/SSD/cost telemetry (PRD §12).
 * Placeholder values in mono type; the live ops feed lands with the
 * operations-dashboard stories (Epic 9). Desktop only (hidden below `lg`).
 */
export function BottomStrip() {
  return (
    <footer className="hidden h-9 shrink-0 items-center gap-6 border-t border-border bg-surface px-6 font-mono text-xs text-text-secondary lg:flex">
      <span className="flex items-center gap-2">
        <span
          className="inline-block h-2 w-2 rounded-full bg-text-secondary"
          aria-hidden
        />
        agents: —
      </span>
      <span>RAM —</span>
      <span>SSD —</span>
      <span>cost —</span>
    </footer>
  );
}
