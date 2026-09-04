"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { getDemoMode, setDemoMode as putDemoMode } from "@/lib/apiClient";

type DemoModeContextValue = {
  /** Hide Portfolio + mask $ amounts/holdings everywhere. Persisted server-side. */
  demoMode: boolean;
  /** True once the initial GET has resolved (so callers can avoid a flash of the wrong state). */
  loaded: boolean;
  setDemoMode: (value: boolean) => Promise<void>;
};

const DemoModeContext = createContext<DemoModeContextValue | null>(null);

/** Fetches the persisted Demo Mode setting once on mount; `setDemoMode` writes through to the
 * backend before updating local state, so a failed PUT doesn't leave the UI out of sync. */
export function DemoModeProvider({ children }: { children: React.ReactNode }) {
  const [demoMode, setDemoModeState] = useState(false);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getDemoMode()
      .then((v) => {
        if (!cancelled) setDemoModeState(v.demoMode);
      })
      .finally(() => {
        if (!cancelled) setLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const setDemoMode = useCallback(async (value: boolean) => {
    const result = await putDemoMode(value);
    setDemoModeState(result.demoMode);
  }, []);

  const value = useMemo(() => ({ demoMode, loaded, setDemoMode }), [demoMode, loaded, setDemoMode]);

  return <DemoModeContext.Provider value={value}>{children}</DemoModeContext.Provider>;
}

export function useDemoMode() {
  const ctx = useContext(DemoModeContext);
  if (!ctx) throw new Error("useDemoMode must be used within DemoModeProvider");
  return ctx;
}
