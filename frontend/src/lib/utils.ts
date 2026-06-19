import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

/**
 * tailwind-merge taught about the §12 `@theme` color tokens. Without this,
 * twMerge doesn't recognize `text-accent`, `bg-surface`, `text-text-secondary`,
 * etc. as colors, so it can't resolve conflicts between them (e.g. an active
 * `text-accent` overriding a base `text-text-secondary`).
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      color: [
        "background",
        "surface",
        "border",
        "accent",
        "gains",
        "losses",
        "warning",
        "text-primary",
        "text-secondary",
      ],
    },
  },
});

/**
 * Merge conditional class names and resolve Tailwind conflicts.
 * The shadcn/ui convention — lets real shadcn components drop in later.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
