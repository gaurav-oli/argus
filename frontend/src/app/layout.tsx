import type { Metadata, Viewport } from "next";
import { Fraunces, Inter, JetBrains_Mono, Space_Grotesk, VT323 } from "next/font/google";
import "./globals.css";
import { ThemeProvider, ThemeScript } from "@/components/theme/ThemeProvider";

// Self-hosted at build time (no runtime CDN request) — works offline on the Mini.
const inter = Inter({
  variable: "--font-inter",
  subsets: ["latin"],
});

const jetbrainsMono = JetBrains_Mono({
  variable: "--font-jetbrains-mono",
  subsets: ["latin"],
});

// Display face for headings + hero numerals — the Cinematic Glass design language.
const spaceGrotesk = Space_Grotesk({
  variable: "--font-space-grotesk",
  subsets: ["latin"],
});

// Private Bank Editorial skin (Home page trial) — a soft high-contrast display serif with
// optical sizing, used for the big numerals and headings on that page only.
const fraunces = Fraunces({
  variable: "--font-fraunces",
  style: ["normal", "italic"],
  subsets: ["latin"],
});

// Terminal Noir (2026-10) — the CRT display face for big numerals, page titles and the wordmark.
// Bitmap-style, single weight; body copy stays JetBrains Mono.
const vt323 = VT323({
  variable: "--font-vt323",
  weight: "400",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Argus",
  description: "AI-powered investment intelligence.",
};

// Browser chrome follows the theme; `viewportFit: cover` exposes the iOS
// safe-area insets the mobile bottom nav pads against.
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f7f9" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0a08" },
  ],
  viewportFit: "cover",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${inter.variable} ${jetbrainsMono.variable} ${spaceGrotesk.variable} ${fraunces.variable} ${vt323.variable} h-full antialiased`}
      suppressHydrationWarning
    >
      <head>
        <ThemeScript />
      </head>
      <body className="min-h-full">
        <ThemeProvider>{children}</ThemeProvider>
      </body>
    </html>
  );
}
