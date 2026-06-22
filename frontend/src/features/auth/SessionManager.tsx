"use client";

import { listSessions, revokeSession, type SessionInfo } from "@/lib/apiClient";
import { useEffect, useState } from "react";

function relativeTime(iso: string | null): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  const mins = Math.round((Date.now() - then) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.round(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.round(hrs / 24)}d ago`;
}

/**
 * Profile → Security: active sessions with remote kill (FR-39 / Story 2.7). Lists every signed-in
 * device; the owner can terminate a lost device's session from here. Killing the current session
 * reloads to the lock screen.
 */
export function SessionManager() {
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listSessions()
      .then((s) => {
        if (active) setSessions(s);
      })
      .catch(() => {
        /* leave empty */
      });
    return () => {
      active = false;
    };
  }, []);

  function refresh() {
    listSessions()
      .then(setSessions)
      .catch(() => setSessions([]));
  }

  async function handleRevoke(s: SessionInfo) {
    setError(null);
    try {
      await revokeSession(s.handle);
      if (s.current) {
        window.location.reload(); // killed our own session → back to the lock screen
        return;
      }
      refresh();
    } catch {
      setError("Couldn't end that session");
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-text-primary">Active sessions</h3>
      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}
      <ul className="flex flex-col gap-2">
        {sessions.map((s) => (
          <li
            key={s.handle}
            className="flex items-center justify-between rounded-lg border border-border bg-background px-3 py-2"
          >
            <span className="text-sm text-text-primary">
              {s.device}
              {s.current && <span className="ml-2 text-xs text-accent">This device</span>}
              <span className="ml-2 text-xs text-text-secondary">active {relativeTime(s.lastActiveAt)}</span>
            </span>
            <button
              onClick={() => handleRevoke(s)}
              className="text-xs font-medium text-text-secondary transition-colors hover:text-losses"
            >
              {s.current ? "Sign out" : "End session"}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
