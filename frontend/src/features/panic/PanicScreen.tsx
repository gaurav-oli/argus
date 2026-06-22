"use client";

/**
 * Neutral cover shown the instant panic mode fires (FR-37). Deliberately innocuous — no Argus
 * branding, no finances, nothing that hints a finance app is being hidden. Looks like a generic
 * blank/loading screen. The session has been destroyed underneath, so tapping through requires
 * re-auth (PIN / Face ID).
 */
export function PanicScreen({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div
      role="presentation"
      onClick={onDismiss}
      className="fixed inset-0 z-[100] flex items-center justify-center bg-background"
    >
      <span
        className="h-6 w-6 animate-spin rounded-full border-2 border-border border-t-transparent"
        aria-hidden
      />
    </div>
  );
}
