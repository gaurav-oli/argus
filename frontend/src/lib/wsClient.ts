// Thin STOMP-over-WebSocket client wrapper for live backend pushes (/topic/...).
// Framework-agnostic; the dashboard shell (Story 1.7) wires it into React.

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8080/ws";

/** How long to wait for the first successful connect before giving up on `ready`
 * (Epic 1 hardening backlog — Story 1.6). Without this, a transport that never connects
 * (backend down, wrong URL, network unreachable) left `ready` permanently unsettled — any
 * caller that legitimately awaits it (its whole purpose) would deadlock forever, since
 * `stomp.js`'s automatic reconnect means neither `onConnect` nor a one-shot error ever fires. */
const CONNECT_TIMEOUT_MS = 15_000;

export function createStompClient(): Client {
  return new Client({ brokerURL: WS_URL, reconnectDelay: 5000 });
}

export interface TopicHandle {
  client: Client;
  /** Resolves once connected and subscribed; rejects if the first connect attempt fails or
   * times out. Only ever settles once — later transport drops/reconnects over the client's
   * life don't re-reject an already-resolved `ready` (a settled Promise can't un-settle). */
  ready: Promise<void>;
  disconnect: () => void;
}

/** Connect and subscribe to a topic, parsing each JSON message body as T. */
export function subscribeToTopic<T>(
  topic: string,
  onMessage: (payload: T) => void,
): TopicHandle {
  const client = createStompClient();
  let subscription: StompSubscription | undefined;

  const ready = new Promise<void>((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error(`[wsClient] connect to ${topic} timed out after ${CONNECT_TIMEOUT_MS}ms`)),
      CONNECT_TIMEOUT_MS,
    );

    client.onConnect = () => {
      clearTimeout(timeout);
      // Reconnects re-fire onConnect on the same Client — drop the prior subscription (tied
      // to the now-dead connection) before re-subscribing, rather than just overwriting the
      // reference and leaking it.
      subscription?.unsubscribe();
      subscription = client.subscribe(topic, (message: IMessage) => {
        // Guard parsing: a malformed/non-JSON frame must not throw inside the
        // STOMP callback (which would kill the subscription handler).
        try {
          onMessage(JSON.parse(message.body) as T);
        } catch {
          console.warn(`[wsClient] dropped non-JSON message on ${topic}`);
        }
      });
      resolve();
    };
    client.onStompError = (frame) => {
      clearTimeout(timeout);
      reject(new Error(frame.headers["message"] ?? "STOMP error"));
    };
    // Transport-level failures — a bad URL, the backend unreachable, a dropped connection
    // before the STOMP handshake completes — hit these, not onStompError (that's for
    // protocol-level errors after a WebSocket is already up).
    client.onWebSocketError = (event) => {
      clearTimeout(timeout);
      reject(new Error(`[wsClient] WebSocket error connecting to ${topic}: ${String(event)}`));
    };
    client.onWebSocketClose = (event) => {
      clearTimeout(timeout);
      reject(new Error(`[wsClient] WebSocket closed before connecting to ${topic} (code ${event.code})`));
    };
  });
  // A rejected `ready` is expected whenever the caller doesn't await it (most don't — they
  // just fire-and-forget subscribeToTopic and rely on the onMessage callback), which would
  // otherwise surface as an unhandled promise rejection in the console on every dropped
  // connection. stomp.js's own reconnect loop is still driving retries underneath regardless.
  ready.catch(() => {});

  client.activate();

  return {
    client,
    ready,
    disconnect: () => {
      subscription?.unsubscribe();
      void client.deactivate();
    },
  };
}
