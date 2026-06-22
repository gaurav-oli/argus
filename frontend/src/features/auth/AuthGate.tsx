"use client";

import { type AuthStatus, getAuthStatus } from "@/lib/apiClient";
import { useEffect, useState } from "react";
import { PinScreen } from "./PinScreen";

type Gate = "loading" | "setup" | "login" | "authed" | "error";

function toGate(status: AuthStatus): Gate {
  if (status.authenticated) return "authed";
  return status.pinSet ? "login" : "setup";
}

/**
 * Client-side auth gate (Story 2.1). On mount it asks the backend for auth status and routes to
 * first-launch PIN setup, the lock screen, or the app. Wraps the dashboard shell so unauthenticated
 * users never see it (the backend also gates /api/** independently — this is UX, not the enforcement).
 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const [gate, setGate] = useState<Gate>("loading");

  useEffect(() => {
    let active = true;
    getAuthStatus()
      .then((status) => {
        if (active) setGate(toGate(status));
      })
      .catch(() => {
        if (active) setGate("error");
      });
    return () => {
      active = false;
    };
  }, []);

  function retry() {
    setGate("loading");
    getAuthStatus()
      .then((status) => setGate(toGate(status)))
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

  return (
    <PinScreen mode={gate} onAuthenticated={() => setGate("authed")} onPinExists={() => setGate("login")} />
  );
}
