"use client";

import { useEffect } from "react";

/**
 * S-D3 — last-resort boundary for an error in the root layout itself. It replaces the whole document, so it
 * carries its own <html>/<body> and inline Terminal Noir colours (globals.css may not have loaded).
 */
export default function GlobalError({
  error,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <html lang="en">
      <body style={{ margin: 0, minHeight: "100vh", background: "#0a0a08", color: "#f4eedc", fontFamily: "ui-monospace, Menlo, monospace" }}>
        <title>Argus — something went wrong</title>
        <main style={{ maxWidth: 480, margin: "15vh auto 0", padding: 24, border: "1px solid #5a4200" }}>
          <p style={{ color: "#ffb000", fontSize: 11, letterSpacing: "0.16em", textTransform: "uppercase" }}>ARGUS · fatal</p>
          <h1 style={{ fontSize: 20, margin: "8px 0" }}>Argus couldn&apos;t start this screen.</h1>
          <p style={{ fontSize: 12, color: "#b8b09a" }}>
            Nothing was lost. Try again, or reload the page.
            {error.digest && <span style={{ display: "block", paddingTop: 4, opacity: 0.7 }}>ref {error.digest}</span>}
          </p>
          <button
            type="button"
            onClick={() => unstable_retry()}
            style={{ marginTop: 16, background: "transparent", color: "#ffb000", border: "1px solid #ffb000", padding: "6px 12px", fontFamily: "inherit", fontSize: 11, textTransform: "uppercase", letterSpacing: "0.1em", cursor: "pointer" }}
          >
            Try again
          </button>
        </main>
      </body>
    </html>
  );
}
