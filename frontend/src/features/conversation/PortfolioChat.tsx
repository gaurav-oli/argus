"use client";

import { ChatPanel } from "@/features/conversation/ChatPanel";
import { sendPortfolioChat } from "@/lib/apiClient";

/**
 * Ask AI about the whole portfolio (Story 7.2, FR-31) — a thin wrapper over the shared
 * {@link ChatPanel} that posts to the dashboard portfolio endpoint. Answers are grounded server-side
 * in holdings + health + upcoming calendar events + recent recommendations + the investor profile.
 */
export function PortfolioChat({ onClose }: { onClose: () => void }) {
  return (
    <ChatPanel
      title="Ask AI"
      subtitle="Portfolio"
      emptyStateText="Ask about your whole portfolio — allocation and health, what to watch before upcoming events, or how recent recommendations fit your holdings."
      placeholder="Ask about your portfolio…"
      sendFn={(messages, signal) => sendPortfolioChat(messages, signal)}
      onClose={onClose}
    />
  );
}
