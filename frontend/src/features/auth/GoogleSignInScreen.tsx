"use client";

import { AmbientBackground } from "@/components/shell/AmbientBackground";
import { googleSignInUrl } from "@/lib/apiClient";
import { motion, useReducedMotion } from "motion/react";

export type SignInReason = "failed" | "not_invited" | null;

const MESSAGES: Record<Exclude<SignInReason, null>, string> = {
  failed: "Sign-in didn't go through — try again.",
  not_invited: "That Google account hasn't been invited yet. Ask the admin to add you.",
};

/**
 * Full-screen Google Sign-In gate — replaces the PIN/passkey lock screen (multi-user). Deliberately
 * wrapped in `.editorial-theme` + `AmbientBackground` itself: `AuthGate` renders this OUTSIDE the
 * dashboard shell (where the theme class normally lives), so without this the screen a visitor sees
 * first would be the one place in the app with none of the real brand on it at all. A plain link to
 * the backend's OAuth start endpoint; Google and the backend do the rest, redirecting back here with
 * `?auth=failed`/`?auth=not_invited` on a rejection (see GoogleAuthController).
 */
export function GoogleSignInScreen({ reason }: { reason: SignInReason }) {
  const reduce = useReducedMotion();
  const rise = (delay: number) =>
    reduce ? {} : { initial: { opacity: 0, y: 14 }, animate: { opacity: 1, y: 0 }, transition: { duration: 0.6, delay, ease: [0.16, 1, 0.3, 1] as const } };

  return (
    <main className="editorial-theme relative flex min-h-dvh items-center justify-center overflow-hidden bg-background px-6">
      <AmbientBackground />
      <div className="relative z-10 w-full max-w-sm text-center">
        <motion.p {...rise(0)} className="text-[11px] font-medium uppercase tracking-[0.3em] text-text-secondary">
          Private &amp; Invite-Only
        </motion.p>

        <motion.h1 {...rise(0.12)} className="font-serif-editorial mt-3 text-5xl font-normal tracking-tight text-text-primary">
          Argus
        </motion.h1>

        <motion.div
          initial={reduce ? undefined : { scaleX: 0 }}
          animate={{ scaleX: 1 }}
          transition={{ duration: 0.8, delay: 0.5, ease: "easeOut" }}
          style={{ transformOrigin: "left" }}
          className="mx-auto mt-5 h-px w-20 bg-accent"
        />

        <motion.p {...rise(0.75)} className="mt-5 text-sm leading-relaxed text-text-secondary">
          Your fifteen-agent research desk.
          <br />
          Sign in to continue.
        </motion.p>

        {reason && (
          <motion.p initial={reduce ? undefined : { opacity: 0 }} animate={{ opacity: 1 }} className="mt-5 text-sm text-losses" role="alert">
            {MESSAGES[reason]}
          </motion.p>
        )}

        <motion.a
          {...rise(1)}
          href={googleSignInUrl()}
          whileHover={reduce ? undefined : { borderColor: "var(--color-accent)" }}
          whileTap={reduce ? undefined : { scale: 0.98 }}
          className="group mt-10 flex w-full items-center justify-center gap-3 border border-[var(--hairline)] py-3.5 text-sm font-medium text-text-primary transition-colors"
        >
          <GoogleLogo />
          Sign in with Google
        </motion.a>
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
