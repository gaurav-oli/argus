// Registers the Web Push service worker app-wide (Epic 8, FR-17). instrumentation-client runs in the
// browser after the HTML loads and before hydration (Next 16 file convention) — the right place for an
// early, idempotent side-effect like SW registration. Registering here (not only on the Profile page)
// means a subscribed device keeps receiving pushes regardless of which page is open, and the Profile
// hook can rely on navigator.serviceWorker.ready. See node_modules/next/dist/docs PWA guide.
if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
  navigator.serviceWorker
    .register("/sw.js", { scope: "/", updateViaCache: "none" })
    .catch((err) => {
      console.error("Argus service worker registration failed:", err);
    });
}
