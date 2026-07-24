"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

/**
 * Institution -> public domain, matched by substring (case-insensitive) so minor variations in
 * how a statement's institution string got parsed ("RBC Direct Investing", "RBC") still resolve.
 * Covers the bank list `ImportStatement` offers (BANKS); "Other"/unmapped names fall back to an
 * initial-letter icon, same as {@link CompanyIcon}.
 */
const BANK_DOMAINS: [match: string, domain: string][] = [
  ["national bank", "nbc.ca"],
  ["rbc", "rbc.com"],
  ["td", "td.com"],
  ["scotiabank", "scotiabank.com"],
  ["scotia", "scotiabank.com"],
  ["bmo", "bmo.com"],
  ["cibc", "cibc.com"],
  ["wealthsimple", "wealthsimple.com"],
  ["questrade", "questrade.com"],
];

function domainFor(institution: string): string | null {
  const lower = institution.toLowerCase();
  for (const [match, domain] of BANK_DOMAINS) {
    if (lower.includes(match)) return domain;
  }
  return null;
}

/**
 * Bank/institution icon — a public favicon lookup (Google's favicon service, no API key/backend
 * round-trip needed) for known institutions, falling back to a colored initial otherwise. Visual
 * treatment matches {@link CompanyIcon} (rounded square, same size/radius scaling).
 */
export function BankIcon({
  institution,
  size = 20,
  className,
}: {
  institution: string;
  size?: number;
  className?: string;
}) {
  const [failed, setFailed] = useState(false);
  const domain = domainFor(institution);
  const initial = institution.trim().charAt(0).toUpperCase() || "?";
  const radius = Math.min(10, Math.round(size * 0.36));

  if (domain && !failed) {
    return (
      <img
        src={`https://www.google.com/s2/favicons?domain=${domain}&sz=64`}
        alt=""
        loading="lazy"
        onError={() => setFailed(true)}
        style={{ borderRadius: radius, height: size, width: size }}
        className={cn("shrink-0 object-contain", className)}
      />
    );
  }
  return (
    <span
      aria-hidden
      style={{ borderRadius: radius, height: size, width: size, fontSize: Math.max(9, size * 0.4) }}
      className={cn(
        "flex shrink-0 items-center justify-center bg-[var(--hover-wash)] font-semibold text-text-secondary",
        className,
      )}
    >
      {initial}
    </span>
  );
}
