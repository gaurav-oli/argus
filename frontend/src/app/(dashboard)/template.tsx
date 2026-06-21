"use client";

import { motion } from "motion/react";

/**
 * A `template` re-mounts on every navigation (unlike `layout`), so wrapping the
 * children here gives each route a fresh fade + lift transition.
 */
export default function DashboardTemplate({ children }: { children: React.ReactNode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.22, 1, 0.36, 1] }}
    >
      {children}
    </motion.div>
  );
}
