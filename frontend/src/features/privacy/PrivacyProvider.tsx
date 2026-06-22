"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";

type PrivacyContextValue = {
  /** Whether sensitive values are currently shown. */
  revealed: boolean;
  reveal: () => void;
  hide: () => void;
  toggle: () => void;
};

const PrivacyContext = createContext<PrivacyContextValue | null>(null);

/**
 * Tap-to-reveal privacy (FR-36 / Story 2.4). Sensitive values are hidden by default; revealing is
 * a single in-memory flag for the whole session, so it **persists within the session and resets on
 * lock** (the lock flow reloads the app, remounting this provider in the hidden state).
 */
export function PrivacyProvider({ children }: { children: React.ReactNode }) {
  const [revealed, setRevealed] = useState(false);

  const reveal = useCallback(() => setRevealed(true), []);
  const hide = useCallback(() => setRevealed(false), []);
  const toggle = useCallback(() => setRevealed((r) => !r), []);

  const value = useMemo(() => ({ revealed, reveal, hide, toggle }), [revealed, reveal, hide, toggle]);

  return <PrivacyContext.Provider value={value}>{children}</PrivacyContext.Provider>;
}

export function usePrivacy() {
  const ctx = useContext(PrivacyContext);
  if (!ctx) throw new Error("usePrivacy must be used within PrivacyProvider");
  return ctx;
}
