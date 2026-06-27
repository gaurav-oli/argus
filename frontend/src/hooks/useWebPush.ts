"use client";

import { useCallback, useEffect, useState } from "react";

import { getPushKey, subscribePush, unsubscribePush } from "@/lib/apiClient";

/**
 * VAPID applicationServerKey must be a Uint8Array, not the base64url string. Backed by an explicit
 * ArrayBuffer so the type is `Uint8Array<ArrayBuffer>` (a valid BufferSource), not `ArrayBufferLike`.
 */
function urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) {
    out[i] = raw.charCodeAt(i);
  }
  return out;
}

export type PushStatus =
  | "loading"
  | "unsupported"
  | "default"
  | "denied"
  | "subscribed"
  | "unconfigured";

export interface WebPush {
  status: PushStatus;
  busy: boolean;
  error: string | null;
  enable: () => Promise<void>;
  disable: () => Promise<void>;
}

/**
 * Manages this device's Web Push subscription (Epic 8, FR-17): permission prompt → PushManager
 * subscribe with the server's VAPID key → persist to the backend. The service worker itself is
 * registered globally in instrumentation-client; this hook relies on serviceWorker.ready.
 */
export function useWebPush(): WebPush {
  const [status, setStatus] = useState<PushStatus>("loading");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const supported =
    typeof window !== "undefined" &&
    "serviceWorker" in navigator &&
    "PushManager" in window &&
    "Notification" in window;

  useEffect(() => {
    let active = true;
    (async () => {
      if (!supported) {
        if (active) setStatus("unsupported");
        return;
      }
      try {
        const reg = await navigator.serviceWorker.ready;
        const sub = await reg.pushManager.getSubscription();
        if (!active) return;
        if (sub) setStatus("subscribed");
        else setStatus(Notification.permission === "denied" ? "denied" : "default");
      } catch {
        if (active) setStatus(Notification.permission === "denied" ? "denied" : "default");
      }
    })();
    return () => {
      active = false;
    };
  }, [supported]);

  const enable = useCallback(async () => {
    if (!supported) return;
    setBusy(true);
    setError(null);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        setStatus(permission === "denied" ? "denied" : "default");
        return;
      }
      const { publicKey } = await getPushKey();
      if (!publicKey) {
        setStatus("unconfigured");
        return;
      }
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
      await subscribePush(sub.toJSON());
      setStatus("subscribed");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't enable notifications");
    } finally {
      setBusy(false);
    }
  }, [supported]);

  const disable = useCallback(async () => {
    if (!supported) return;
    setBusy(true);
    setError(null);
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.getSubscription();
      if (sub) {
        const { endpoint } = sub;
        await sub.unsubscribe();
        try {
          await unsubscribePush(endpoint);
        } catch {
          // best-effort — the local subscription is already gone
        }
      }
      setStatus(Notification.permission === "denied" ? "denied" : "default");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't disable notifications");
    } finally {
      setBusy(false);
    }
  }, [supported]);

  return { status, busy, error, enable, disable };
}
