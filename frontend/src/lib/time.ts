/**
 * Absolute local date + time for an ISO instant, e.g. "Jul 6, 11:42 a.m." (same year) or
 * "Jul 6, 2025, 11:42 a.m." (other years). Empty string for null. Used where an exact
 * "last refreshed at" reads better than a relative "4h ago".
 */
export function absTime(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const sameYear = d.getFullYear() === new Date().getFullYear();
  return d.toLocaleString("en-CA", {
    month: "short",
    day: "numeric",
    year: sameYear ? undefined : "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}

/** Relative "time ago" (past) / "in N" (future) for an ISO instant. Empty string for null. */
export function relTime(iso: string | null): string {
  if (!iso) return "";
  const ms = new Date(iso).getTime() - Date.now();
  const fut = ms > 0;
  const m = Math.round(Math.abs(ms) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return fut ? `in ${m}m` : `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return fut ? `in ${h}h` : `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 7) return fut ? `in ${d}d` : `${d}d ago`;
  return new Date(iso).toLocaleDateString("en-CA", { month: "short", day: "numeric" });
}
