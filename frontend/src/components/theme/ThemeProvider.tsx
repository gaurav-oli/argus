"use client";

import { createContext, useCallback, useContext, useState } from "react";

type Theme = "light" | "dark";

type ThemeContextValue = {
  theme: Theme;
  toggle: () => void;
  setTheme: (t: Theme) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

/** Storage key + the inline script (see ThemeScript) must agree. */
const STORAGE_KEY = "argus-theme";

function applyTheme(theme: Theme) {
  const root = document.documentElement;
  root.classList.toggle("dark", theme === "dark");
  root.style.colorScheme = theme;
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  // Read the theme the no-FOUC script already applied to <html> (lazy initializer,
  // so no setState-in-effect). Server renders the "dark" default; the client
  // reconciles to the real value on first render (<html> has suppressHydrationWarning).
  const [theme, setThemeState] = useState<Theme>(() =>
    typeof document !== "undefined" && !document.documentElement.classList.contains("dark") ? "light" : "dark",
  );

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t);
    applyTheme(t);
    try {
      localStorage.setItem(STORAGE_KEY, t);
    } catch {
      /* private mode / storage disabled — theme still applies for the session */
    }
  }, []);

  const toggle = useCallback(() => {
    setTheme(theme === "dark" ? "light" : "dark");
  }, [theme, setTheme]);

  return <ThemeContext.Provider value={{ theme, toggle, setTheme }}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error("useTheme must be used within ThemeProvider");
  return ctx;
}

/**
 * Inline, render-blocking script: sets the theme class BEFORE first paint so
 * there is no light→dark flash. Defaults to dark (the premium default) unless
 * the user previously chose light. Injected in <head> via layout.tsx.
 */
export function ThemeScript() {
  const js = `(function(){try{var q=new URLSearchParams(location.search).get('theme');var t=q==='light'||q==='dark'?q:localStorage.getItem('${STORAGE_KEY}');if(t!=='light'){document.documentElement.classList.add('dark');document.documentElement.style.colorScheme='dark';}else{document.documentElement.style.colorScheme='light';}}catch(e){document.documentElement.classList.add('dark');}})();`;
  return <script dangerouslySetInnerHTML={{ __html: js }} />;
}
