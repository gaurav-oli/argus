import type { MetadataRoute } from "next";

/**
 * PWA manifest (PRD §12) — makes Argus installable on iPhone/iPad/desktop.
 * Service worker + Web Push are deferred to the notifications story (Epic 8);
 * the manifest alone is enough for "Add to Home Screen".
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Argus — Paper-validated research lab",
    short_name: "Argus",
    description: "A paper-validated research lab: AI agents make calls, test them on paper trades, and learn. Not brokerage advice; never places orders.",
    start_url: "/",
    display: "standalone",
    background_color: "#0A0A08",
    theme_color: "#0A0A08",
    icons: [
      // Raster PNGs first — iOS Safari "Add to Home Screen" ignores SVG icons.
      { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      // The L3 A-eye mark sits inside the maskable safe zone (inner 80% circle), so reuse it.
      // Sources + generator: frontend/brand/ (`node brand/build-icons.mjs`).
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
      // Scalable extra for browsers that honor it.
      { src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
    ],
  };
}
