"use client";

import { cn } from "@/lib/utils";
import { ApiError, biometricLogin, login, setupPin, webauthnSupported } from "@/lib/apiClient";
import { useState } from "react";

type Mode = "setup" | "login";

const PIN_OK = /^\d{4,6}$/;

/** Surface a useful message: the server's problem detail/status if any, else the fallback. */
function describe(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return `${fallback} (${err.status}${err.problem.detail ? `: ${err.problem.detail}` : ""})`;
  }
  return `${fallback} — can't reach Argus`;
}

/**
 * Full-screen PIN gate (Story 2.1). `setup` collects + confirms a new 4–6 digit PIN on first
 * launch; `login` unlocks an existing one. On success the backend has set the session cookie,
 * so we call {@link onAuthenticated} to reveal the app.
 */
export function PinScreen({
  mode,
  passkeyEnrolled = false,
  onAuthenticated,
  onPinExists,
}: {
  mode: Mode;
  passkeyEnrolled?: boolean;
  onAuthenticated: () => void;
  onPinExists: () => void;
}) {
  const [pin, setPin] = useState("");
  const [firstPin, setFirstPin] = useState<string | null>(null); // setup: the entry being confirmed
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const canUseBiometric = mode === "login" && passkeyEnrolled && webauthnSupported();

  async function handleBiometric() {
    setError(null);
    setBusy(true);
    try {
      await biometricLogin();
      onAuthenticated();
    } catch (err) {
      // Cancel/timeout/failure: stay on the PIN screen (never dead-end). AC #3.
      const msg = err instanceof ApiError ? "Biometric unlock failed — use your PIN" : "Use your PIN";
      setError(msg);
    } finally {
      setBusy(false);
    }
  }

  const confirming = mode === "setup" && firstPin !== null;
  const heading = mode === "login" ? "Enter your PIN" : confirming ? "Confirm your PIN" : "Set a PIN";
  const subtext =
    mode === "login"
      ? "Unlock Argus"
      : confirming
        ? "Re-enter the PIN to confirm"
        : "Choose a 4–6 digit PIN to secure Argus";

  function reset(message: string) {
    setError(message);
    setPin("");
    setFirstPin(null);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!PIN_OK.test(pin)) {
      setError("PIN must be 4–6 digits");
      return;
    }

    if (mode === "setup" && !confirming) {
      setFirstPin(pin);
      setPin("");
      return;
    }

    if (mode === "setup" && confirming) {
      if (pin !== firstPin) {
        reset("PINs didn't match — start again");
        return;
      }
      setBusy(true);
      try {
        await setupPin(firstPin);
      } catch (err) {
        setBusy(false);
        // 409 = a PIN already exists (e.g. a prior attempt created it). Route to the lock screen
        // rather than logging in with this just-typed PIN, which may differ from the stored one.
        if (err instanceof ApiError && err.status === 409) {
          onPinExists();
          return;
        }
        reset(describe(err, "Couldn't set your PIN"));
        return;
      }
      try {
        await login(firstPin);
        onAuthenticated();
      } catch (err) {
        reset(describe(err, "PIN set, but sign-in failed"));
      } finally {
        setBusy(false);
      }
      return;
    }

    // login
    setBusy(true);
    try {
      await login(pin);
      onAuthenticated();
    } catch (err) {
      const msg = err instanceof ApiError && err.status === 401 ? "Incorrect PIN" : describe(err, "Sign-in failed");
      setError(msg);
      setPin("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-background px-6">
      <div className="w-full max-w-xs text-center">
        <h1 className="text-2xl font-semibold text-text-primary">Argus</h1>
        <p className="mt-1 text-sm text-text-secondary">{subtext}</p>

        <form onSubmit={handleSubmit} className="mt-8 flex flex-col gap-4">
          <label className="sr-only" htmlFor="pin">
            {heading}
          </label>
          <input
            id="pin"
            type="password"
            inputMode="numeric"
            autoComplete="off"
            autoFocus
            maxLength={6}
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
            aria-invalid={error !== null}
            className={cn(
              "rounded-xl border bg-surface px-4 py-3 text-center font-mono text-2xl tracking-[0.5em] text-text-primary outline-none",
              "focus:border-accent",
              error ? "border-losses" : "border-border",
            )}
          />

          <p className="min-h-5 text-sm text-losses" role="alert">
            {error ?? ""}
          </p>

          <button
            type="submit"
            disabled={busy || !PIN_OK.test(pin)}
            className={cn(
              "rounded-xl bg-accent px-4 py-3 font-medium text-background transition-opacity",
              "disabled:cursor-not-allowed disabled:opacity-40",
            )}
          >
            {busy ? "Please wait…" : confirming ? "Confirm" : mode === "setup" ? "Continue" : "Unlock"}
          </button>
        </form>

        {canUseBiometric && (
          <button
            type="button"
            onClick={handleBiometric}
            disabled={busy}
            className="mt-4 w-full rounded-xl border border-border bg-surface px-4 py-3 font-medium text-text-primary transition-colors hover:border-accent disabled:opacity-40"
          >
            Unlock with Face ID / Touch ID
          </button>
        )}
      </div>
    </main>
  );
}
