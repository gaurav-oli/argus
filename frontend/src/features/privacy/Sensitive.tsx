"use client";

import { cn } from "@/lib/utils";
import { usePrivacy } from "./PrivacyProvider";

/**
 * Wraps a sensitive value (FR-36). Hidden by default as `••••••`; tapping any masked value reveals
 * all sensitive values for the session. When revealed, renders {@link children} unchanged. Pass
 * {@link className} so the mask matches the value's typography/size.
 */
export function Sensitive({ children, className }: { children: React.ReactNode; className?: string }) {
  const { revealed, reveal } = usePrivacy();

  if (revealed) {
    return <>{children}</>;
  }

  return (
    <button
      type="button"
      onClick={reveal}
      aria-label="Tap to reveal"
      title="Tap to reveal"
      className={cn(
        "cursor-pointer font-mono tracking-widest text-text-secondary tabular-nums select-none",
        className,
      )}
    >
      ••••••
    </button>
  );
}
