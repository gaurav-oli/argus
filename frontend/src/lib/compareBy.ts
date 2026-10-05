export type SortDir = "asc" | "desc";

/**
 * Comparator for table sorting: numbers numerically, everything else as text, and nulls always last
 * whichever way the column is sorted (a missing value is never "the biggest" or "the smallest").
 */
export function compareBy<T>(get: (row: T) => number | string | null, dir: SortDir) {
  return (a: T, b: T) => {
    const x = get(a);
    const y = get(b);
    if (x == null && y == null) return 0;
    if (x == null) return 1;
    if (y == null) return -1;
    const c = typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y));
    return dir === "asc" ? c : -c;
  };
}
