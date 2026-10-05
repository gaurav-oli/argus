"use client";

import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useMemo, useRef, useState } from "react";

export interface PaletteItem {
  id: string;
  icon: string;
  label: string;
  hint?: string;
  onSelect: () => void;
}

/**
 * ⌘K-style jump palette for the Intelligence page: tickers and agent labs, filtered as you type.
 * Opens with ⌘K/Ctrl+K from anywhere on the page, or a click on the search field. A spring pop-in on
 * the panel, a soft fade on the backdrop, and the selected row cross-fades in — not just a modal that
 * appears, one that arrives.
 */
export function CommandPalette({ items }: { items: PaletteItem[] }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  const reduce = useReducedMotion();

  function openPalette() {
    setQuery("");
    setOpen(true);
    requestAnimationFrame(() => inputRef.current?.focus());
  }

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((v) => {
          if (!v) {
            setQuery("");
            requestAnimationFrame(() => inputRef.current?.focus());
          }
          return !v;
        });
      } else if (e.key === "Escape") {
        setOpen(false);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return items.slice(0, 8);
    return items.filter((i) => i.label.toLowerCase().includes(q)).slice(0, 12);
  }, [items, query]);

  return (
    <>
      <button
        type="button"
        onClick={openPalette}
        className="flex w-full max-w-[380px] items-center gap-2 rounded border border-[var(--hairline)] bg-[var(--hover-wash)] px-3 py-1.5 text-left text-[12.5px] text-text-tertiary transition-colors hover:border-accent/40"
      >
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" className="shrink-0">
          <circle cx="11" cy="11" r="7" />
          <path d="M21 21l-4.3-4.3" />
        </svg>
        <span className="flex-1 truncate">Jump to a ticker, agent or strategy…</span>
        <span className="shrink-0 rounded border border-border px-1.5 py-0.5 font-mono text-[9.5px]">⌘K</span>
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            className="fixed inset-0 z-[100] flex items-start justify-center pt-[14vh]"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: reduce ? 0 : 0.15 }}
            onClick={() => setOpen(false)}
          >
            <div className="absolute inset-0 bg-black/55" />
            <motion.div
              role="dialog"
              aria-modal
              onClick={(e) => e.stopPropagation()}
              initial={reduce ? false : { opacity: 0, y: -10, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -6, scale: 0.98 }}
              transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 420, damping: 32 }}
              className="relative w-[560px] max-w-[90vw] overflow-hidden rounded border border-border bg-elevated shadow-2xl"
            >
              <div className="flex items-center gap-2.5 border-b border-[var(--hairline)] px-4 py-3">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" className="shrink-0 text-text-tertiary">
                  <circle cx="11" cy="11" r="7" />
                  <path d="M21 21l-4.3-4.3" />
                </svg>
                <input
                  ref={inputRef}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Jump to a ticker, agent or strategy…"
                  className="flex-1 bg-transparent text-[13.5px] text-text-primary outline-none placeholder:text-text-tertiary"
                />
                <span className="shrink-0 rounded border border-border px-1.5 py-0.5 font-mono text-[9.5px] text-text-tertiary">ESC</span>
              </div>
              <div className="max-h-[320px] overflow-y-auto p-1.5">
                {filtered.length === 0 ? (
                  <p className="px-3 py-6 text-center text-[12.5px] text-text-secondary">No match.</p>
                ) : (
                  filtered.map((item, i) => (
                    <motion.button
                      key={item.id}
                      type="button"
                      initial={reduce ? false : { opacity: 0, x: -4 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: reduce ? 0 : i * 0.02 }}
                      onClick={() => {
                        item.onSelect();
                        setOpen(false);
                      }}
                      className="flex w-full items-center gap-3 rounded px-2.5 py-2 text-left text-[13px] transition-colors hover:bg-[var(--hover-wash)]"
                    >
                      <span className="w-4 shrink-0 text-center text-[11px] text-text-tertiary">{item.icon}</span>
                      <span className="min-w-0 flex-1 truncate">{item.label}</span>
                      {item.hint && <span className="shrink-0 text-[10.5px] text-text-tertiary">{item.hint}</span>}
                    </motion.button>
                  ))
                )}
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
