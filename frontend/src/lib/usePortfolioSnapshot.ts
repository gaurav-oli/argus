"use client";

import { getPortfolioValue, type PortfolioSnapshot } from "@/lib/apiClient";
import { REFRESH, useAutoRefresh } from "@/lib/useAutoRefresh";
import { subscribeToTopic, type TopicHandle } from "@/lib/wsClient";
import { useEffect, useState } from "react";

// One shared live portfolio for every component that shows it (top-bar total, allocation, treemap):
// a single /user/queue/portfolio subscription however many are mounted, plus a REST re-pull on the
// normal cadence in case the socket silently dropped. Before this, those components loaded the value
// once and showed it until a full page reload.
let current: PortfolioSnapshot | null = null;
let lastFetch = 0;
let socket: TopicHandle | null = null;
const listeners = new Set<(s: PortfolioSnapshot) => void>();

function publish(s: PortfolioSnapshot) {
  current = s;
  listeners.forEach((l) => l(s));
}

/** The signed-in person's live portfolio snapshot, or null until the first one arrives. */
export function usePortfolioSnapshot(): PortfolioSnapshot | null {
  const [snap, setSnap] = useState<PortfolioSnapshot | null>(current);

  useEffect(() => {
    listeners.add(setSnap);
    if (!socket) {
      socket = subscribeToTopic<PortfolioSnapshot>("/user/queue/portfolio", publish);
      socket.ready.catch(() => {}); // the REST re-pull below covers a socket that never connects
    }
    return () => {
      listeners.delete(setSnap);
      if (listeners.size === 0) {
        socket?.disconnect();
        socket = null;
        current = null; // never hand one person's snapshot to the next sign-in in this tab
      }
    };
  }, []);

  useAutoRefresh(() => {
    if (Date.now() - lastFetch < 10_000) return; // several mounted consumers share one fetch
    lastFetch = Date.now();
    return getPortfolioValue().then(publish).catch(() => {});
  }, REFRESH.NORMAL);

  return snap;
}
