"use client";

import { logout } from "@/lib/apiClient";
import { useState } from "react";

/**
 * Ends the session (Story 2.1) and reloads, so {@link import("./AuthGate").AuthGate} re-checks
 * status and shows the lock screen. A reload is the simplest correct reset of all app state.
 */
export function LogoutButton() {
  const [busy, setBusy] = useState(false);

  async function handleLogout() {
    setBusy(true);
    try {
      await logout();
    } finally {
      window.location.reload();
    }
  }

  return (
    <button
      onClick={handleLogout}
      disabled={busy}
      className="rounded-xl border border-border bg-surface px-4 py-2 text-sm font-medium text-text-primary transition-colors hover:border-losses hover:text-losses disabled:opacity-40"
    >
      {busy ? "Locking…" : "Lock & sign out"}
    </button>
  );
}
