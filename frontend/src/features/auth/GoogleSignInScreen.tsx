"use client";

import { googleSignInUrl } from "@/lib/apiClient";

export type SignInReason = "failed" | "not_invited" | null;

const MESSAGES: Record<Exclude<SignInReason, null>, string> = {
  failed: "Sign-in didn't go through — try again.",
  not_invited: "That Google account hasn't been invited yet. Ask the admin to add you.",
};

/**
 * Full-screen Google Sign-In gate — replaces the PIN/passkey lock screen (multi-user). A plain link
 * to the backend's OAuth start endpoint; Google and the backend do the rest, redirecting back here
 * with `?auth=failed`/`?auth=not_invited` on a rejection (see GoogleAuthController).
 */
export function GoogleSignInScreen({ reason }: { reason: SignInReason }) {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-background px-6">
      <div className="w-full max-w-xs text-center">
        <h1 className="text-2xl font-semibold text-text-primary">Argus</h1>
        <p className="mt-1 text-sm text-text-secondary">Sign in to continue.</p>

        {reason && (
          <p className="mt-4 text-sm text-losses" role="alert">
            {MESSAGES[reason]}
          </p>
        )}

        <a
          href={googleSignInUrl()}
          className="mt-8 flex w-full items-center justify-center gap-3 rounded-xl border border-border bg-surface px-4 py-3 font-medium text-text-primary transition-colors hover:border-accent"
        >
          <GoogleLogo />
          Sign in with Google
        </a>
      </div>
    </main>
  );
}

function GoogleLogo() {
  return (
    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
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
