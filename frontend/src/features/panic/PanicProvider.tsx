"use client";

import { logout } from "@/lib/apiClient";
import { useCallback, useState } from "react";
import { PanicScreen } from "./PanicScreen";
import { usePanicGestures } from "./usePanicGestures";

/**
 * Panic mode (FR-37 / Story 2.5). A configured gesture instantly covers the app with a neutral
 * screen AND destroys the session, so the only way back is re-auth (PIN / Face ID). Mounted inside
 * the authenticated shell, so the gestures are active only while signed in.
 */
export function PanicProvider({ children }: { children: React.ReactNode }) {
  const [panicked, setPanicked] = useState(false);

  const panic = useCallback(() => {
    setPanicked(true); // instant neutral cover (synchronous, before any await)
    void logout().catch(() => {
      /* even if the network call fails, the cover stays and dismiss → re-auth */
    });
  }, []);

  usePanicGestures(panic);

  // Dismiss → reload: the session is gone, so AuthGate lands on the lock screen (re-auth required).
  const dismiss = useCallback(() => window.location.reload(), []);

  return (
    <>
      {children}
      {panicked && <PanicScreen onDismiss={dismiss} />}
    </>
  );
}
