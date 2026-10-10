"use client";

import { ArgusMark } from "@/components/brand/ArgusMark";
import { AmbientBackground } from "@/components/shell/AmbientBackground";
import { googleSignInUrl } from "@/lib/apiClient";
import { useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";

export type SignInReason = "failed" | "not_invited" | null;

const MESSAGES: Record<Exclude<SignInReason, null>, string> = {
  failed: "Sign-in didn't go through — try again.",
  not_invited: "That Google account hasn't been invited yet. Ask the admin to add you.",
};

/** The boot log, printed one line at a time before the login prompt appears. */
const BOOT_LINES: { text: string; status?: string }[] = [
  { text: "ARGUS research terminal" },
  { text: "probing 15 agents", status: "OK" },
  { text: "mounting market feeds", status: "OK" },
  { text: "loading model gateway", status: "OK" },
  { text: "isolating portfolios per user", status: "OK" },
  { text: "mode: paper lab. calls are tested on paper; no orders placed." },
  { text: "access: invite-only. identity required." },
];
const LINE_MS = 280;

/**
 * Full-screen Google Sign-In gate — replaces the PIN/passkey lock screen (multi-user). Terminal
 * Noir: a short boot log prints line by line, then a `login:` prompt with the Google button. Wrapped
 * in `.terminal-theme` + `AmbientBackground` itself because `AuthGate` renders this OUTSIDE the
 * dashboard shell (where the theme class normally lives). A plain link to the backend's OAuth start
 * endpoint; Google and the backend do the rest, redirecting back here with
 * `?auth=failed`/`?auth=not_invited` on a rejection (see GoogleAuthController). Reduced motion
 * skips straight to the finished screen.
 */
export function GoogleSignInScreen({ reason }: { reason: SignInReason }) {
  const reduce = useReducedMotion();
  const [lines, setLines] = useState(0);

  useEffect(() => {
    if (reduce) return;
    const id = setInterval(() => {
      setLines((n) => {
        if (n >= BOOT_LINES.length) {
          clearInterval(id);
          return n;
        }
        return n + 1;
      });
    }, LINE_MS);
    return () => clearInterval(id);
  }, [reduce]);

  const shown = reduce ? BOOT_LINES.length : lines;
  const booted = shown >= BOOT_LINES.length;

  return (
    <main className="terminal-theme relative flex min-h-dvh items-center justify-center overflow-hidden bg-background px-6">
      <AmbientBackground />
      <div className="relative z-10 w-full max-w-md font-mono text-sm">
        <div className="flex items-center gap-5 text-accent">
          <ArgusMark size={88} className="drop-shadow-[0_0_14px_rgba(255,176,0,0.45)]" />
          <div>
            <h1 className="font-display term-glow text-6xl leading-none tracking-[0.06em] text-accent">ARGUS</h1>
            <p className="mt-1 text-xs uppercase tracking-[0.25em] text-text-secondary">Private &amp; invite-only</p>
          </div>
        </div>

        <ol className="mt-8 min-h-[11rem] space-y-1.5" aria-label="Startup">
          {BOOT_LINES.slice(0, shown).map((l) => (
            <li key={l.text} className="term-line-in flex gap-2 text-text-primary">
              <span className="text-accent" aria-hidden>
                &gt;
              </span>
              <span className="flex-1">{l.text}</span>
              {l.status && <span className="text-gains">[ {l.status} ]</span>}
            </li>
          ))}
        </ol>

        {reason && (
          <p className="mt-4 border border-losses/60 px-3 py-2 text-losses" role="alert">
            ! {MESSAGES[reason]}
          </p>
        )}

        {booted && (
          <div className="term-line-in mt-6">
            <p className="text-accent">
              login:<span className="term-caret" aria-hidden />
            </p>
            <a
              href={googleSignInUrl()}
              className="mt-4 flex w-full items-center justify-center gap-3 border border-accent py-3.5 uppercase tracking-wider text-accent transition-colors hover:bg-accent hover:text-background"
            >
              <GoogleLogo />
              Sign in with Google
            </a>
          </div>
        )}
      </div>
    </main>
  );
}

function GoogleLogo() {
  return (
    <svg width="17" height="17" viewBox="0 0 48 48" aria-hidden="true">
      <path
        fill="#FFC107"
        d="M43.6 20.5H42V20H24v8h11.3c-1.6 4.7-6.1 8-11.3 8-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.1 8 3l5.7-5.7C34.6 6 29.6 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.7-.4-3.5z"
      />
      <path
        fill="#FF3D00"
        d="m6.3 14.7 6.6 4.8C14.6 15.9 18.9 13 24 13c3.1 0 5.8 1.1 8 3l5.7-5.7C34.6 6 29.6 4 24 4c-7.5 0-14 4.2-17.7 10.7z"
      />
      <path
        fill="#4CAF50"
        d="M24 44c5.5 0 10.4-1.9 14.3-5.1l-6.6-5.6C29.6 35.3 26.9 36 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.9 39.7 16.4 44 24 44z"
      />
      <path
        fill="#1976D2"
        d="M43.6 20.5H42V20H24v8h11.3c-.8 2.3-2.3 4.2-4.2 5.6l6.6 5.6C41.4 36 44 30.6 44 24c0-1.3-.1-2.7-.4-3.5z"
      />
    </svg>
  );
}
