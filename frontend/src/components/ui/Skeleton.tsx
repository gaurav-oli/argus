import { cn } from "@/lib/utils";

/**
 * Animated placeholder block shown while content loads.
 * Reusable building block — used in route `loading` states and panels.
 */
export function Skeleton({
  className,
  ...props
}: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      // Decorative by default — screen readers skip the empty boxes; loading
      // regions announce via their own role="status" wrapper. Overridable.
      aria-hidden="true"
      className={cn("animate-pulse rounded-md bg-border/60", className)}
      {...props}
    />
  );
}
