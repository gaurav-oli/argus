"use client";

import { logout } from "@/lib/apiClient";
import { useCallback, useRef, useState } from "react";
import { PanicScreen } from "./PanicScreen";
import { usePanicGestures } from "./usePanicGestures";

/** Cap on how long dismiss waits for the logout call before reloading anyway. */
const LOGOUT_WAIT_MS = 4000;

/**
 * Panic mode (FR-37 / Story 2.5). A configured gesture instantly covers the app with a neutral
 * screen AND destroys the session, so the only way back is re-auth (PIN / Face ID). Mounted inside
 * the authenticated shell, so the gestures are active only while signed in.
 *
 * <p>Dismiss waits for the logout to actually land before reloading, so a fast tap can't reload
 * (aborting the in-flight logout) and slip back in on a still-valid session. If logout genuinely
 * fails/times out, the reload still lands on the lock screen when the session is gone; a surviving
 * session then falls back to the Story 2.3 idle timeout.
 */
export function PanicProvider({ children }: { children: React.ReactNode }) {
  const [panicked, setPanicked] = useState(false);
  const logoutDone = useRef<Promise<unknown> | null>(null);

  const panic = useCallback(() => {
    setPanicked(true); // instant neutral cover (synchronous, before any await)
    logoutDone.current = logout().catch(() => {
      /* swallow — dismiss still reloads to the lock screen */
    });
  }, []);

  usePanicGestures(panic);

  const dismiss = useCallback(async () => {
    await Promise.race([
      logoutDone.current ?? Promise.resolve(),
      new Promise((r) => setTimeout(r, LOGOUT_WAIT_MS)),
    ]);
    window.location.reload();
  }, []);

  return (
    <>
      {children}
      {panicked && <PanicScreen onDismiss={dismiss} />}
    </>
  );
}
