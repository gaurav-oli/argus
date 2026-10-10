"use client";

import Link from "next/link";
import { useEffect } from "react";

/**
 * S-D3 — a page that throws while rendering shows this inside the shell (sidebar, top bar and the paper-lab
 * banner stay), instead of blanking the app. "Try again" re-fetches and re-renders the page (Next 16's
 * `unstable_retry`).
 */
export default function DashboardError({
  error,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div className="mx-auto mt-16 max-w-lg border border-losses/40 bg-losses/[0.05] p-6 font-mono">
      <p className="text-[11px] uppercase tracking-[0.16em] text-losses">! page error</p>
      <h2 className="mt-2 font-display text-xl text-text-primary">This page hit a problem.</h2>
      <p className="mt-2 text-xs text-text-secondary">
        The rest of Argus is fine. Your data isn&apos;t affected — this view just failed to draw.
        {error.digest && <span className="block pt-1 text-text-tertiary">ref {error.digest}</span>}
      </p>
      <div className="mt-4 flex gap-3">
        <button
          type="button"
          onClick={() => unstable_retry()}
          className="border border-accent px-3 py-1.5 text-[11px] uppercase tracking-wider text-accent hover:bg-accent/10"
        >
          Try again
        </button>
        <Link href="/" className="px-3 py-1.5 text-[11px] uppercase tracking-wider text-text-secondary hover:text-text-primary">
          Go home
        </Link>
      </div>
    </div>
  );
}
