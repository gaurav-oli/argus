"use client";

import { cn } from "@/lib/utils";
import {
  enrollPasskey,
  listPasskeys,
  type PasskeyInfo,
  revokePasskey,
  webauthnSupported,
} from "@/lib/apiClient";
import { useEffect, useState } from "react";

/** Default label for a new passkey, inferred from the device. */
function deviceLabel(): string {
  if (typeof navigator === "undefined") return "Passkey";
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Mac/.test(ua)) return "Mac";
  return "Passkey";
}

/**
 * Profile → Security: enroll Face/Touch ID passkeys and revoke them (Story 2.2). Rendered for an
 * authenticated user; enrollment runs the WebAuthn registration ceremony against the live session.
 */
export function PasskeyManager() {
  const [passkeys, setPasskeys] = useState<PasskeyInfo[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const supported = webauthnSupported();

  useEffect(() => {
    let active = true;
    listPasskeys()
      .then((list) => {
        if (active) setPasskeys(list);
      })
      .catch(() => {
        /* leave the list empty; the enroll button still works */
      });
    return () => {
      active = false;
    };
  }, []);

  function refresh() {
    listPasskeys()
      .then(setPasskeys)
      .catch(() => setPasskeys([]));
  }

  async function handleEnroll() {
    setError(null);
    setBusy(true);
    try {
      await enrollPasskey(deviceLabel());
      refresh();
    } catch {
      setError("Couldn't enable biometrics — try again");
    } finally {
      setBusy(false);
    }
  }

  async function handleRevoke(id: string) {
    setError(null);
    try {
      await revokePasskey(id);
      refresh();
    } catch {
      setError("Couldn't remove that passkey");
    }
  }

  if (!supported) {
    return (
      <p className="text-sm text-text-secondary">
        This device doesn&apos;t support biometric unlock — use your PIN.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <button
        onClick={handleEnroll}
        disabled={busy}
        className={cn(
          "self-start rounded-xl bg-accent px-4 py-2 text-sm font-medium text-background transition-opacity",
          "disabled:cursor-not-allowed disabled:opacity-40",
        )}
      >
        {busy ? "Waiting for biometrics…" : "Enable Face ID / Touch ID"}
      </button>

      {error && (
        <p className="text-sm text-losses" role="alert">
          {error}
        </p>
      )}

      {passkeys.length > 0 && (
        <ul className="flex flex-col gap-2">
          {passkeys.map((pk) => (
            <li
              key={pk.id}
              className="flex items-center justify-between rounded-lg border border-border bg-background px-3 py-2"
            >
              <span className="text-sm text-text-primary">
                {pk.label}
                <span className="ml-2 text-xs text-text-secondary">
                  added {new Date(pk.createdAt).toLocaleDateString()}
                </span>
              </span>
              <button
                onClick={() => handleRevoke(pk.id)}
                className="text-xs font-medium text-text-secondary transition-colors hover:text-losses"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
