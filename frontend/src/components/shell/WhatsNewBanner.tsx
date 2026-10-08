"use client";

import { getUnreadAnnouncements, markAnnouncementRead, type Announcement } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { motion, useReducedMotion } from "motion/react";
import { useState } from "react";

/**
 * "What's new" — the newest announcement this person hasn't read, shown at the top of every page the next
 * time they open Argus (on any device) until they tap "Got it", which marks it read for them alone. Collapsed
 * to the first few points on small screens so it never buries the page it sits on.
 */
export function WhatsNewBanner() {
  const [note, setNote] = useState<Announcement | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [busy, setBusy] = useState(false);
  const reduce = useReducedMotion();

  // Re-checked slowly, so someone already signed in when a note is published still gets it.
  useAutoRefresh(
    () =>
      getUnreadAnnouncements()
        .then((list) => setNote(list[0] ?? null))
        .catch(() => {}),
    REFRESH.SLOW,
  );

  if (!note) return null;

  const PREVIEW = 3;
  const points = expanded ? note.points : note.points.slice(0, PREVIEW);
  const hidden = note.points.length - points.length;

  async function gotIt() {
    if (!note) return;
    setBusy(true);
    try {
      await markAnnouncementRead(note.id);
    } catch {
      // Hide it for now anyway; it comes back next session if the server didn't record it.
    }
    setNote(null);
    setBusy(false);
  }

  return (
    <motion.section
      aria-label={note.title}
      initial={reduce ? false : { height: 0, opacity: 0 }}
      animate={{ height: "auto", opacity: 1 }}
      transition={{ duration: 0.25, ease: "easeOut" }}
      className="overflow-hidden"
    >
      <div className="border-b border-accent/30 bg-accent/[0.06] px-4 py-3 sm:px-6">
        <div className="flex items-start justify-between gap-3">
          <p className="font-display text-sm font-semibold text-accent">✦ {note.title}</p>
          <button
            type="button"
            onClick={() => void gotIt()}
            disabled={busy}
            className="shrink-0 border border-accent/50 px-2.5 py-1 font-mono text-[11px] font-semibold text-accent transition-colors hover:bg-accent hover:text-background disabled:opacity-50"
          >
            Got it
          </button>
        </div>
        <ul className="mt-2 flex flex-col gap-1.5">
          {points.map((p, i) => (
            <li key={i} className="flex gap-2 text-xs leading-relaxed text-text-primary">
              <span aria-hidden className="text-accent">
                ›
              </span>
              <span>{p}</span>
            </li>
          ))}
        </ul>
        {note.points.length > PREVIEW && (
          <button type="button" onClick={() => setExpanded((e) => !e)} className="mt-2 font-mono text-[11px] text-accent hover:underline">
            {expanded ? "Show less" : `Show ${hidden} more`}
          </button>
        )}
      </div>
    </motion.section>
  );
}
