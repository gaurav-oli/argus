// Thin STOMP-over-WebSocket client wrapper for live backend pushes (/topic/...).
// Framework-agnostic; the dashboard shell (Story 1.7) wires it into React.

import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs";

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8080/ws";

export function createStompClient(): Client {
  return new Client({ brokerURL: WS_URL, reconnectDelay: 5000 });
}

export interface TopicHandle {
  client: Client;
  /** Resolves once connected and subscribed. */
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
    client.onConnect = () => {
      subscription = client.subscribe(topic, (message: IMessage) => {
        onMessage(JSON.parse(message.body) as T);
      });
      resolve();
    };
    client.onStompError = (frame) => reject(new Error(frame.headers["message"] ?? "STOMP error"));
  });

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
