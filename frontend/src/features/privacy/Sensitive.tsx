"use client";

import { cn } from "@/lib/utils";
import { useDemoMode } from "./DemoModeProvider";
import { usePrivacy } from "./PrivacyProvider";

const MASK_CLASSNAME = "font-mono tracking-widest text-text-secondary tabular-nums select-none";

/**
 * Wraps a sensitive value (FR-36). Hidden by default as `••••••`; tapping any masked value reveals
 * all sensitive values for the session. When revealed, renders {@link children} unchanged. Pass
 * {@link className} so the mask matches the value's typography/size.
 *
 * When Demo Mode is on, the mask is always shown with no reveal control — there must be no escape
 * hatch mid-demo, so this takes priority over the session-scoped {@link usePrivacy} reveal state.
 */
export function Sensitive({ children, className }: { children: React.ReactNode; className?: string }) {
  const { demoMode } = useDemoMode();
  const { revealed, reveal } = usePrivacy();

  if (demoMode) {
    return (
      <span aria-label="Hidden" className={cn(MASK_CLASSNAME, className)}>
        ••••••
      </span>
    );
  }

  if (revealed) {
    return <>{children}</>;
  }

  return (
    <button
      type="button"
      onClick={reveal}
      aria-label="Tap to reveal"
      title="Tap to reveal"
      className={cn("cursor-pointer", MASK_CLASSNAME, className)}
    >
      ••••••
    </button>
  );
}
