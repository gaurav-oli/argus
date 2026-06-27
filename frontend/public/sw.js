// Argus Web Push service worker (Epic 8, FR-17). It receives push payloads from the backend —
// JSON of the shape { title, body, url } — and shows an OS notification. Clicking a notification
// focuses an existing Argus tab (navigating it to the payload's url) or opens a new one.

self.addEventListener("push", function (event) {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (err) {
    data = { title: "Argus", body: event.data ? event.data.text() : "" };
  }

  const title = data.title || "Argus";
  const options = {
    body: data.body || "",
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    vibrate: [100, 50, 100],
    // CRITICAL alerts set requireInteraction so the notification stays until acted on (Story 8.2).
    requireInteraction: !!data.requireInteraction,
    data: { url: data.url || "/" },
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", function (event) {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || "/";

  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then(function (clientList) {
        // Prefer a tab already on the target path.
        for (const client of clientList) {
          try {
            if (new URL(client.url).pathname === target && "focus" in client) {
              return client.focus();
            }
          } catch (err) {
            /* ignore malformed client urls */
          }
        }
        // Otherwise focus any open Argus tab and navigate it.
        for (const client of clientList) {
          if ("focus" in client && "navigate" in client) {
            return client.focus().then((c) => (c && c.navigate ? c.navigate(target) : c));
          }
        }
        // No open tab — open a fresh window.
        if (self.clients.openWindow) {
          return self.clients.openWindow(target);
        }
      }),
  );
});
