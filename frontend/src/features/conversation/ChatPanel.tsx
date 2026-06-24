"use client";

import { ApiError, type ChatMessage } from "@/lib/apiClient";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";

/** Mirror of the backend per-message cap (ChatValidation.MAX_MESSAGE_CHARS). */
const MAX_MESSAGE_CHARS = 4000;

/** A chat turn plus whether it was produced by escalation to Claude Haiku (Story 7.3). */
type Msg = ChatMessage & { viaHaiku?: boolean };

/**
 * Shared Ask-AI chat panel (Stories 7.1/7.2/7.3). A dismissible centered-modal chat whose answers
 * are grounded server-side; the caller supplies a {@link sendFn} that posts the thread (with a
 * `deeper` escalation flag) to the relevant endpoint. Stateless: the full thread is sent each turn
 * and lives only for the open session. A "warming up" indicator covers the local model's cold-load;
 * a "Get deeper analysis" action escalates the last question to Claude Haiku.
 */
export function ChatPanel({
  title,
  subtitle,
  emptyStateText,
  placeholder,
  sendFn,
  onClose,
}: {
  title: string;
  subtitle?: string;
  emptyStateText: string;
  placeholder: string;
  sendFn: (messages: ChatMessage[], deeper: boolean, signal?: AbortSignal) => Promise<ChatMessage>;
  onClose: () => void;
}) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reduceMotion = useReducedMotion();
  const threadRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Abort any in-flight request only on real unmount (panel close) — kept separate from the
  // [onClose] effect so an unstable onClose from a re-rendering parent can't abort mid-request.
  useEffect(() => () => abortRef.current?.abort(), []);

  // Keep the latest turn in view as the thread grows.
  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, pending]);

  function describeError(err: unknown): string {
    if (err instanceof ApiError) {
      return err.problem.detail ?? err.problem.title ?? `Request failed (${err.status}).`;
    }
    return "Couldn't reach the model. Please try again.";
  }

  /** Strip local-only fields before sending the thread to the backend. */
  function asChatMessages(msgs: Msg[]): ChatMessage[] {
    return msgs.map((m) => ({ role: m.role, content: m.content }));
  }

  async function send() {
    const question = input.trim();
    if (!question || pending) return;

    const next: Msg[] = [...messages, { role: "user", content: question }];
    setMessages(next);
    setInput("");
    setError(null);
    setPending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const reply = await sendFn(asChatMessages(next), false, controller.signal);
      setMessages((m) => [...m, reply]);
    } catch (err) {
      if (controller.signal.aborted) return;
      setMessages((m) => m.slice(0, -1)); // drop the unanswered turn so it isn't resent
      setInput(question);
      setError(describeError(err));
    } finally {
      if (!controller.signal.aborted) setPending(false);
      abortRef.current = null;
    }
  }

  /** Re-answer the last question via Claude Haiku ("deeper analysis"). */
  async function escalate() {
    if (pending) return;
    let lastUser = -1;
    for (let i = messages.length - 1; i >= 0; i--) {
      if (messages[i].role === "user") {
        lastUser = i;
        break;
      }
    }
    if (lastUser < 0) return;
    const thread = asChatMessages(messages.slice(0, lastUser + 1));

    setError(null);
    setPending(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const reply = await sendFn(thread, true, controller.signal);
      setMessages((m) => [...m, { ...reply, viaHaiku: true }]);
    } catch (err) {
      if (controller.signal.aborted) return;
      setError(describeError(err));
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

  const last = messages[messages.length - 1];
  // Only offer "deeper analysis" for the last answer when the user isn't mid-typing a new
  // question (otherwise it would deepen a stale question while the new one sits unsent).
  const canDeepen = !pending && !input.trim() && last?.role === "assistant" && !last.viaHaiku;

  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm" onClick={onClose} aria-hidden />
      <motion.div
        role="dialog"
        aria-label={subtitle ? `${title} — ${subtitle}` : title}
        initial={reduceMotion ? false : { opacity: 0, y: 24, scale: 0.98 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ type: "spring", stiffness: 220, damping: 26 }}
        className="fixed left-1/2 top-1/2 z-50 flex max-h-[80dvh] w-[min(36rem,92vw)] -translate-x-1/2 -translate-y-1/2 flex-col overflow-hidden rounded-2xl border border-border bg-surface shadow-[0_24px_60px_-24px_rgba(2,6,23,0.7)]"
      >
        <header className="flex items-center justify-between border-b border-border px-5 py-3.5">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-text-primary">{title}</span>
            {subtitle && (
              <span className="rounded bg-accent/15 px-1.5 py-0.5 text-[10px] font-medium text-accent">
                {subtitle}
              </span>
            )}
          </div>
          <button onClick={onClose} aria-label="Close" className="text-text-secondary hover:text-text-primary">
            ✕
          </button>
        </header>

        <div ref={threadRef} className="flex flex-1 flex-col gap-3 overflow-y-auto px-5 py-4">
          {messages.length === 0 && !pending && (
            <p className="text-sm text-text-secondary">{emptyStateText}</p>
          )}
          {messages.map((m, i) => (
            <Bubble key={i} message={m} reduceMotion={!!reduceMotion} />
          ))}
          {pending && <Thinking />}
          {error && <p className="text-sm text-losses">{error}</p>}
          {canDeepen && (
            <button
              onClick={escalate}
              className="self-start text-[11px] font-medium text-accent underline-offset-2 hover:underline"
            >
              ✦ Get deeper analysis (Claude Haiku)
            </button>
          )}
        </div>

        <div className="flex items-end gap-2 border-t border-border px-4 py-3">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKeyDown}
            rows={1}
            maxLength={MAX_MESSAGE_CHARS}
            placeholder={placeholder}
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

function Bubble({ message, reduceMotion }: { message: Msg; reduceMotion: boolean }) {
  const isUser = message.role === "user";
  return (
    <motion.div
      initial={reduceMotion ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      className={`flex max-w-[85%] flex-col gap-1 ${isUser ? "self-end" : "self-start"}`}
    >
      <div
        className={`whitespace-pre-wrap rounded-2xl px-3.5 py-2 text-sm ${
          isUser
            ? "bg-accent/15 text-text-primary"
            : "border border-border bg-elevated text-text-primary"
        }`}
      >
        {message.content}
      </div>
      {message.viaHaiku && (
        <span className="text-[10px] font-medium text-accent">via Claude Haiku</span>
      )}
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
