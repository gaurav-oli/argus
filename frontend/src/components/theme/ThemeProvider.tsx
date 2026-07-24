"use client";

import { createContext, useContext } from "react";

type Theme = "dark";

type ThemeContextValue = {
  theme: Theme;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * The app is a single fixed dark palette (Private Bank Editorial, 2026-07-22) — there is no
 * light variant and no toggle. This context now exists only so existing consumers (e.g.
 * PortfolioChart, which re-reads CSS vars keyed off `theme`) don't need to change; `theme` is
 * always "dark".
 */
export function ThemeProvider({ children }: { children: React.ReactNode }) {
  return <ThemeContext.Provider value={{ theme: "dark" }}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}

/**
 * Inline, render-blocking script: forces the dark class + color-scheme BEFORE first paint, so
 * there's no flash and no dependency on stored state. Previously this read a `localStorage`
 * theme preference from the old light/dark toggle (removed 2026-07-22) — a stale "light" value
 * left over from that toggle would still set `color-scheme: light`, which native form controls
 * (select dropdowns, the date picker) follow independently of the app's own CSS, rendering them
 * with light/white chrome against the otherwise-dark app. Also clears that stale key so it can't
 * resurface if the toggle or a light variant ever comes back. Injected in <head> via layout.tsx.
 */
export function ThemeScript() {
  const js = `(function(){document.documentElement.classList.add('dark');document.documentElement.style.colorScheme='dark';try{localStorage.removeItem('argus-theme');}catch(e){}})();`;
  return <script dangerouslySetInnerHTML={{ __html: js }} />;
}
