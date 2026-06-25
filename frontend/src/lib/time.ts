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
