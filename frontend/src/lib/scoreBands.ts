/**
 * Single source of truth for mapping a 0–100 score to a display band, shared across the dashboard and
 * portfolio views so the health badge, the health ring, and the credibility colors never drift apart
 * (they previously hardcoded three different threshold sets). Backend health/credibility scores stay
 * pure numbers; these bands are presentation only.
 */

export const HEALTH_HEALTHY = 75;
export const HEALTH_BALANCED = 50;

export interface HealthBand {
  label: string;
  /** Tailwind text-color class (badge, labels). */
  textClass: string;
  /** CSS variable for a fill/stroke (the ring). */
  colorVar: string;
}

export function healthBand(score: number | null | undefined): HealthBand {
  if (score == null) {
    return { label: "—", textClass: "text-text-secondary", colorVar: "var(--color-warning)" };
  }
  if (score >= HEALTH_HEALTHY) {
    return { label: "Healthy", textClass: "text-gains", colorVar: "var(--chart-gains)" };
  }
  if (score >= HEALTH_BALANCED) {
    return { label: "Balanced", textClass: "text-warning", colorVar: "var(--chart-accent)" };
  }
  return { label: "At risk", textClass: "text-losses", colorVar: "var(--color-warning)" };
}

export const RISK_HIGH = 70;
export const RISK_MEDIUM = 40;

/** Color class for a 0–100 risk/credibility score: high = concerning (red), medium = caution. */
export function riskColorClass(score: number): string {
  if (score >= RISK_HIGH) return "text-losses";
  if (score >= RISK_MEDIUM) return "text-warning";
  return "text-text-primary";
}
