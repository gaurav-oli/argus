import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Emit a self-contained server (.next/standalone) so the Docker runtime image
  // is lean and needs no full node_modules. Used by the Mac Mini deploy (Story 1.8).
  output: "standalone",
};

export default nextConfig;
