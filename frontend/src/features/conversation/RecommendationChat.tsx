"use client";

import { ChatPanel } from "@/features/conversation/ChatPanel";
import { sendRecommendationChat } from "@/lib/apiClient";

/**
 * Ask AI about a recommendation (Story 7.1, FR-30) — a thin wrapper over the shared {@link ChatPanel}
 * that posts to the per-recommendation endpoint and grounds answers in that recommendation's
 * signals/diagnostic + the user's portfolio.
 */
export function RecommendationChat({
  recommendationId,
  ticker,
  onClose,
}: {
  recommendationId: number;
  ticker: string;
  onClose: () => void;
}) {
  return (
    <ChatPanel
      title="Ask AI"
      subtitle={ticker}
      emptyStateText={`Ask anything about this recommendation — its signals, the diagnostic, or how it fits your portfolio. Answers are grounded in ${ticker}'s data.`}
      placeholder={`Ask about ${ticker}…`}
      sendFn={(messages, signal) => sendRecommendationChat(recommendationId, messages, signal)}
      onClose={onClose}
    />
  );
}
