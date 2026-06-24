"use client";

import { ApiError, sendRecommendationChat, type ChatMessage } from "@/lib/apiClient";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";

/** Mirror of the backend per-message cap (ConversationController.MAX_MESSAGE_CHARS). */
const MAX_MESSAGE_CHARS = 4000;

/**
 * Ask AI about a recommendation (Story 7.1, FR-30). A dismissible chat panel whose answers are
 * grounded server-side in the recommendation's signals/diagnostic + the user's portfolio, via the
 * Model Gateway. Stateless: the full thread is sent each turn and lives only for the open session
 * (closing the panel ends it). A "warming up" indicator covers the local model's cold-load.
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
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reduceMotion = useReducedMotion();
  const threadRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    // Abort any in-flight request when the panel closes/unmounts so it neither
    // updates an unmounted component nor holds the serialized model slot.
    return () => {
      window.removeEventListener("keydown", onKey);
      abortRef.current?.abort();
    };
  }, [onClose]);

  // Keep the latest turn in view as the thread grows.
  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, pending]);

  async function send() {
    const question = input.trim();
    if (!question || pending) return;

    const next: ChatMessage[] = [...messages, { role: "user", content: question }];
    setMessages(next);
    setInput("");
    setError(null);
    setPending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const reply = await sendRecommendationChat(recommendationId, next, controller.signal);
      setMessages((m) => [...m, reply]);
    } catch (err) {
      if (controller.signal.aborted) return; // panel closed/unmounted — drop silently
      // Drop the unanswered turn so it isn't resent as dangling history, and put the
      // question back in the box for an easy retry. Surface the real problem detail.
      setMessages((m) => m.slice(0, -1));
      setInput(question);
      setError(
        err instanceof ApiError
          ? (err.problem.detail ?? err.problem.title ?? `Request failed (${err.status}).`)
          : "Couldn't reach the model. Please try again.",
      );
    } finally {
      if (!controller.signal.aborted) setPending(false);
      abortRef.current = null;
    }
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm" onClick={onClose} aria-hidden />
      <motion.div
        role="dialog"
        aria-label={`Ask AI about ${ticker}`}
        initial={reduceMotion ? false : { opacity: 0, y: 24, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ type: "spring", stiffness: 220, damping: 26 }}
        className="fixed left-1/2 top-1/2 z-50 flex max-h-[80dvh] w-[min(36rem,92vw)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-[0_24px_60px_-24px_rgba(2,6,23,0.7)]"
      >
        <header className="flex items-center justify-between border-b border-border px-5 py-3.5">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-text-primary">Ask AI</span>
            <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[10px] font-medium text-accent">{ticker}</span>
          </div>
          <button onClick={onClose} aria-label="Close" className="text-text-secondary hover:text-text-primary">
            ✕
          </button>
        </header>

        <div ref={threadRef} className="flex flex-1 flex-col gap-3 overflow-y-auto px-5 py-4">
          {messages.length === 0 && !pending && (
            <p className="text-sm text-text-secondary">
              Ask anything about this recommendation — its signals, the diagnostic, or how it fits your
              portfolio. Answers are grounded in {ticker}&apos;s data.
            </p>
          )}
          {messages.map((m, i) => (
            <Bubble key={i} message={m} reduceMotion={!!reduceMotion} />
          ))}
          {pending && <Thinking />}
          {error && <p className="text-sm text-losses">{error}</p>}
        </div>

        <div className="flex items-end gap-2 border-t border-border px-4 py-3">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            rows={1}
            maxLength={MAX_MESSAGE_CHARS}
            placeholder={`Ask about ${ticker}…`}
            className="max-h-32 flex-1 resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-text-primary placeholder:text-text-secondary focus:border-accent focus:outline-none"
          />
          <button
            onClick={send}
            disabled={pending || !input.trim()}
            className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-background transition-opacity disabled:opacity-40"
          >
            Send
          </button>
        </div>
      </motion.div>
    </>
  );
}

function Bubble({ message, reduceMotion }: { message: ChatMessage; reduceMotion: boolean }) {
  const isUser = message.role === "user";
  return (
    <motion.div
      initial={reduceMotion ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      className={`max-w-[85%] whitespace-pre-wrap rounded-2xl px-3.5 py-2 text-sm ${
        isUser
          ? "self-end bg-accent/15 text-text-primary"
          : "self-start border border-border bg-elevated text-text-primary"
      }`}
    >
      {message.content}
    </motion.div>
  );
}

/** Thinking / warming-up indicator — covers the local model's cold-load (~28s on first use). */
function Thinking() {
  return (
    <div className="flex items-center gap-2 self-start text-sm text-text-secondary">
      <span className="flex gap-1">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="h-1.5 w-1.5 rounded-full bg-text-secondary"
            animate={{ opacity: [0.3, 1, 0.3] }}
            transition={{ duration: 1.1, repeat: Infinity, delay: i * 0.18 }}
          />
        ))}
      </span>
      <span>Thinking — the local model may take a moment to warm up…</span>
    </div>
  );
}
