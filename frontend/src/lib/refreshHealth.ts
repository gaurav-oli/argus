/**
 * S-D3 — whether the app's background refreshes are currently failing, so a page never silently keeps showing
 * stale numbers. Every `apiGet` reports here: a network failure or a 5xx marks refreshes as failing (4xx don't —
 * 404 is how several endpoints say "nothing yet", and 401 re-gates to sign-in); the next success clears it.
 * The shell's banner reads it via {@link subscribe}/{@link snapshot} (useSyncExternalStore). No imports, so the
 * node unit tests can load it directly.
 */

export interface RefreshHealth {
  /** When the last read succeeded (ms since epoch), or null before the first one. */
  lastOkAt: number | null;
  /** When the current run of failures began, or null when refreshes are healthy. */
  failingSince: number | null;
  /** How many reads have failed in a row. */
  failures: number;
}

let state: RefreshHealth = { lastOkAt: null, failingSince: null, failures: 0 };
const listeners = new Set<() => void>();

function set(next: RefreshHealth) {
  state = next;
  listeners.forEach((l) => l());
}

/** Whether a failed response's status means the data couldn't be refreshed (vs. a meaningful 4xx answer). */
export function countsAsRefreshFailure(status: number | null): boolean {
  return status === null || status >= 500;
}

export function reportOk(now: number = Date.now()): void {
  if (state.failingSince === null && state.lastOkAt !== null && now - state.lastOkAt < 1000) return; // avoid churn
  set({ lastOkAt: now, failingSince: null, failures: 0 });
}

/** `status` is the HTTP status, or null for a network failure (offline, backend down, CORS). */
export function reportFailure(status: number | null, now: number = Date.now()): void {
  if (!countsAsRefreshFailure(status)) return;
  set({ lastOkAt: state.lastOkAt, failingSince: state.failingSince ?? now, failures: state.failures + 1 });
}

export function snapshot(): RefreshHealth {
  return state;
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Banner text for a failing state, e.g. "Couldn't refresh — showing data from 10:42 AM. Retrying…". */
export function staleMessage(h: RefreshHealth, formatTime: (ms: number) => string): string | null {
  if (h.failingSince === null) return null;
  return h.lastOkAt === null
    ? "Couldn't reach Argus — data may be missing. Retrying…"
    : `Couldn't refresh — showing data from ${formatTime(h.lastOkAt)}. Retrying…`;
}

/** Test hook: back to the initial state. */
export function resetForTests(): void {
  set({ lastOkAt: null, failingSince: null, failures: 0 });
}
