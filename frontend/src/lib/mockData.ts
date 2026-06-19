/**
 * Dummy data for the dashboard design prototype. No real sources are wired yet
 * — these shapes are intentionally close to what the feature stories will
 * eventually feed in, so swapping mock → live is a data-layer change, not a
 * redesign. (Design-first prototype, branch: design/dashboard-redesign.)
 */

export const portfolio = {
  totalValue: 487293.42,
  dayChange: 8421.16,
  dayChangePct: 1.76,
  costBasis: 412050.0,
  currency: "CAD",
};

export const healthScore = {
  score: 82,
  label: "Healthy",
  delta: 4, // vs last week
};

/** 30-day portfolio value trend (dummy, gently rising). */
export const trend = Array.from({ length: 30 }, (_, i) => {
  const base = 452000;
  const drift = i * 1180;
  const wobble = Math.sin(i / 2.3) * 5200 + Math.cos(i / 1.7) * 2600;
  return {
    day: i + 1,
    value: Math.round(base + drift + wobble),
  };
});

/** Allocation by sector — feeds the donut. */
export const allocation = [
  { name: "Technology", value: 34, color: "#00D4FF" },
  { name: "Financials", value: 22, color: "#00FF88" },
  { name: "Energy", value: 16, color: "#FFB800" },
  { name: "Healthcare", value: 14, color: "#8B5CF6" },
  { name: "Consumer", value: 9, color: "#FF6B9D" },
  { name: "Cash", value: 5, color: "#6B7280" },
];

type Mover = {
  symbol: string;
  name: string;
  price: number;
  changePct: number;
  spark: number[];
};

export const topMovers: Mover[] = [
  { symbol: "SHOP", name: "Shopify", price: 142.8, changePct: 6.4, spark: [120, 124, 122, 130, 134, 138, 143] },
  { symbol: "NVDA", name: "NVIDIA", price: 1284.5, changePct: 3.9, spark: [1190, 1210, 1205, 1240, 1255, 1270, 1285] },
  { symbol: "TD", name: "TD Bank", price: 81.2, changePct: 1.2, spark: [79, 79.5, 80, 79.8, 80.4, 81, 81.2] },
  { symbol: "ENB", name: "Enbridge", price: 58.6, changePct: -2.1, spark: [60.5, 60, 59.4, 59.8, 59, 58.7, 58.6] },
  { symbol: "BCE", name: "BCE Inc", price: 44.3, changePct: -3.4, spark: [46.2, 45.8, 45.5, 45, 44.7, 44.5, 44.3] },
];

type Alert = {
  id: string;
  tier: "critical" | "warning" | "info";
  title: string;
  body: string;
  time: string;
};

export const alerts: Alert[] = [
  { id: "a1", tier: "critical", title: "ENB earnings miss", body: "Enbridge Q2 EPS below consensus — review position.", time: "2m" },
  { id: "a2", tier: "warning", title: "Tech concentration", body: "Technology now 34% of book, above your 30% target.", time: "18m" },
  { id: "a3", tier: "info", title: "BoC rate decision", body: "Bank of Canada announcement tomorrow 10:00 ET.", time: "1h" },
];

export const recommendations = [
  { id: "r1", action: "Trim", symbol: "SHOP", confidence: 0.78, rationale: "Up 6.4% — take partial gains" },
  { id: "r2", action: "Add", symbol: "TD", confidence: 0.64, rationale: "Below 50-day, dividend support" },
];

export const agents = [
  { name: "News", status: "active" as const },
  { name: "Calendar", status: "active" as const },
  { name: "Recommender", status: "active" as const },
  { name: "Sentiment", status: "idle" as const },
];

export const usd = (n: number) =>
  n.toLocaleString("en-CA", { style: "currency", currency: "CAD", maximumFractionDigits: 0 });

export const usdPrecise = (n: number) =>
  n.toLocaleString("en-CA", { style: "currency", currency: "CAD", minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const pct = (n: number) => `${n >= 0 ? "+" : ""}${n.toFixed(2)}%`;
