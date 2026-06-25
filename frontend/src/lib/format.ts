/** Currency + percentage formatters (CAD home currency). Pure utilities — no mock data. */

/** Whole-dollar CAD, e.g. `1284300 → "$1,284,300"`. */
export const usd = (n: number) =>
  n.toLocaleString("en-CA", { style: "currency", currency: "CAD", maximumFractionDigits: 0 });

/** Cent-precise CAD, e.g. `1284300.5 → "$1,284,300.50"`. */
export const usdPrecise = (n: number) =>
  n.toLocaleString("en-CA", {
    style: "currency",
    currency: "CAD",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });

/** Signed percentage, e.g. `2.4 → "+2.40%"`. */
export const pct = (n: number) => `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`;

/** Whole-dollar CAD or an em-dash when the value is null (empty portfolio). */
export const usdOrDash = (n: number | null | undefined) => (n == null ? "—" : usd(n));
