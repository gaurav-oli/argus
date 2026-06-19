import type { MetadataRoute } from "next";

/**
 * PWA manifest (PRD §12) — makes Argus installable on iPhone/iPad/desktop.
 * Service worker + Web Push are deferred to the notifications story (Epic 8);
 * the manifest alone is enough for "Add to Home Screen".
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Argus — Investment Intelligence",
    short_name: "Argus",
    description: "AI-powered investment intelligence.",
    start_url: "/",
    display: "standalone",
    background_color: "#0A0A0F",
    theme_color: "#0A0A0F",
    icons: [
      // Raster PNGs first — iOS Safari "Add to Home Screen" ignores SVG icons.
      { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      // The brand mark sits well inside the maskable safe zone, so reuse it.
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
      // Scalable extra for browsers that honor it.
      { src: "/icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
    ],
  };
}
