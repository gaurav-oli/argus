"use client";

import { useSyncExternalStore } from "react";

const emptySubscribe = () => () => {};

/**
 * False during SSR and the first client render, true thereafter. Gates Recharts'
 * ResponsiveContainer (which measures the DOM — size 0 during SSR) to avoid a
 * hydration mismatch. Uses useSyncExternalStore so there's no setState-in-effect:
 * server snapshot = false, client snapshot = true.
 */
export function useMounted() {
  return useSyncExternalStore(
    emptySubscribe,
    () => true,
    () => false,
  );
}
