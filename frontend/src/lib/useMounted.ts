"use client";

import { useEffect, useState } from "react";

/**
 * True only after the first client render. Used to gate Recharts'
 * ResponsiveContainer, which measures the DOM (size 0 during SSR) and would
 * otherwise cause a hydration mismatch that freezes the surrounding UI.
 */
export function useMounted() {
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  return mounted;
}
