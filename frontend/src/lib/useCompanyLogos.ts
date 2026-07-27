"use client";

import { useEffect, useMemo, useState } from "react";
import { getCompanyLogos } from "@/lib/apiClient";

/**
 * Batch-fetches company logos for a set of tickers, keyed on the sorted-distinct-ticker string
 * (not the input array itself) so a caller re-rendering with a new-but-same-content array — e.g. a
 * live price tick recreating a positions list — doesn't re-fetch on every render. Shared by every
 * ticker-list view that wants a `CompanyIcon`; extracted from `HoldingsTable`'s original inline
 * version once enough call sites repeated the same pattern.
 */
export function useCompanyLogos(tickers: string[]): Record<string, string> {
  const [logos, setLogos] = useState<Record<string, string>>({});
  const tickerKey = useMemo(() => [...new Set(tickers)].sort().join(","), [tickers]);

  useEffect(() => {
    if (!tickerKey) return;
    let active = true;
    getCompanyLogos(tickerKey.split(","))
      .then((m) => active && setLogos((prev) => ({ ...prev, ...m })))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [tickerKey]);

  return logos;
}
