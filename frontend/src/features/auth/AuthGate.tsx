"use client";

import { type AuthStatus, getAuthStatus, getInvestorProfile, setUnauthorizedHandler } from "@/lib/apiClient";
import { useEffect, useState } from "react";
import { GoogleSignInScreen, type SignInReason } from "./GoogleSignInScreen";
import { OnboardingQuestions } from "./OnboardingQuestions";

type Gate = "loading" | "signed-out" | "authed" | "error";
/** Whether this signed-in person still needs the first-login questions (Phase 2). Checked once per
 * mount right after `authed`, not on every render — "done" also covers "already answered before". */
type Onboarding = "checking" | "needed" | "done";

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
  const [onboarding, setOnboarding] = useState<Onboarding>("checking");

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

  // Once signed in, check (once) whether this person still needs the first-login questions.
  useEffect(() => {
    if (gate !== "authed") return;
    let active = true;
    getInvestorProfile()
      .then((p) => active && setOnboarding(p.needsOnboarding ? "needed" : "done"))
      .catch(() => active && setOnboarding("done")); // never trap someone behind this on a network blip
    return () => {
      active = false;
    };
  }, [gate]);

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
    if (onboarding === "needed") {
      return <OnboardingQuestions onDone={() => setOnboarding("done")} />;
    }
    if (onboarding === "checking") {
      return (
        <main className="editorial-theme flex min-h-dvh items-center justify-center bg-background">
          <p className="text-sm text-text-secondary">Loading…</p>
        </main>
      );
    }
    return <>{children}</>;
  }

  // editorial-theme here too: AuthGate renders these outside the dashboard shell, which is the
  // only place that class normally lives — without it, every pre-signed-in state would flash the
  // generic default palette instead of the real brand.
  if (gate === "loading") {
    return (
      <main className="editorial-theme flex min-h-dvh items-center justify-center bg-background">
        <p className="text-sm text-text-secondary">Loading…</p>
      </main>
    );
  }

  if (gate === "error") {
    return (
      <main className="editorial-theme flex min-h-dvh flex-col items-center justify-center gap-4 bg-background px-6 text-center">
        <p className="text-sm text-text-secondary">Can&apos;t reach Argus.</p>
        <button onClick={retry} className="border border-[var(--hairline)] px-4 py-2 font-medium text-accent transition-colors hover:border-accent">
          Retry
        </button>
      </main>
    );
  }

  return <GoogleSignInScreen reason={reason} />;
}
