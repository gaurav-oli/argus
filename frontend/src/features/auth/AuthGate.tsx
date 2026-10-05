"use client";

import { type AuthStatus, getAuthStatus, setUnauthorizedHandler } from "@/lib/apiClient";
import { useEffect, useState } from "react";
import { GoogleSignInScreen, type SignInReason } from "./GoogleSignInScreen";

type Gate = "loading" | "signed-out" | "authed" | "error";

/** The backend redirects back here with `?auth=failed|not_invited` on a rejected Google sign-in
 * (see GoogleAuthController) — read once on mount, not reactively, so this never forces the whole
 * shell into dynamic rendering just to notice a query param. */
function readSignInReason(): SignInReason {
  if (typeof window === "undefined") return null;
  const v = new URLSearchParams(window.location.search).get("auth");
  return v === "failed" || v === "not_invited" ? v : null;
}

/**
 * Client-side auth gate. On mount it asks the backend for auth status and routes to either the
 * Google Sign-In screen or the app. The backend also gates /api/** independently — this is UX, not
 * the enforcement.
 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const [gate, setGate] = useState<Gate>("loading");
  const [reason, setReason] = useState<SignInReason>(null);

  useEffect(() => {
    let active = true;
    getAuthStatus()
      .then((status: AuthStatus) => {
        if (!active) return;
        if (!status.authenticated) setReason(readSignInReason());
        setGate(status.authenticated ? "authed" : "signed-out");
      })
      .catch(() => {
        if (active) setGate("error");
      });
    return () => {
      active = false;
    };
  }, []);

  // Any 401 (e.g. the idle timeout expired) drops back to the sign-in screen. This also unmounts
  // the shell + PrivacyProvider, resetting tap-to-reveal on lock (Demo Mode).
  useEffect(() => {
    setUnauthorizedHandler(() => setGate((g) => (g === "authed" ? "signed-out" : g)));
    return () => setUnauthorizedHandler(null);
  }, []);

  function retry() {
    setGate("loading");
    getAuthStatus()
      .then((status: AuthStatus) => setGate(status.authenticated ? "authed" : "signed-out"))
      .catch(() => setGate("error"));
  }

  if (gate === "authed") {
    return <>{children}</>;
  }

  if (gate === "loading") {
    return (
      <main className="flex min-h-dvh items-center justify-center bg-background">
        <p className="text-sm text-text-secondary">Loading…</p>
      </main>
    );
  }

  if (gate === "error") {
    return (
      <main className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-background px-6 text-center">
        <p className="text-sm text-text-secondary">Can&apos;t reach Argus.</p>
        <button onClick={retry} className="rounded-xl bg-accent px-4 py-2 font-medium text-background">
          Retry
        </button>
      </main>
    );
  }

  return <GoogleSignInScreen reason={reason} />;
}
