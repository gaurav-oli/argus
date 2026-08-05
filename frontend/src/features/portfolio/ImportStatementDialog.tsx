"use client";

import { useEffect } from "react";
import { createPortal } from "react-dom";
import { ImportStatement } from "./ImportStatement";

/**
 * Import is an occasional task (once a month, maybe), not daily content — this moves it off the
 * page into a header action + centered dialog instead of a permanently-rendered widget at the top
 * of the scroll. `ImportStatement` itself is untouched; this only supplies the modal chrome, mirroring
 * the portal + click-away + Escape convention already used for `HealthScoreBreakdown`.
 */
export function ImportStatementDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/60 p-4 pt-[10vh] sm:pt-[15vh]"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Import statement"
        className="w-full max-w-xl rounded-2xl border border-border bg-surface p-5 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-1 flex items-start justify-between gap-3">
          <div />
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="flex h-8 w-8 min-h-[32px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-[var(--hover-wash)] hover:text-text-primary"
          >
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6">
              <path d="M3 3l10 10M13 3L3 13" />
            </svg>
          </button>
        </div>
        <ImportStatement />
      </div>
    </div>,
    document.body,
  );
}
