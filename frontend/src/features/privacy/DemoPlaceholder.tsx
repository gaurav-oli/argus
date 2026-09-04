/** Stand-in for a chart/treemap while Demo Mode is on — those render pixels, not text, so wrapping
 * them in {@link Sensitive} would just hide the whole visualization anyway. Same intent, rendered
 * directly instead. */
export function DemoPlaceholder({ label, className }: { label: string; className?: string }) {
  return (
    <div
      className={`flex h-full min-h-[120px] w-full flex-col items-center justify-center gap-1 rounded-lg border border-dashed border-border text-center ${className ?? ""}`}
    >
      <span className="font-mono text-lg tracking-widest text-text-secondary select-none">••••••</span>
      <span className="text-xs text-text-secondary">{label}</span>
    </div>
  );
}
