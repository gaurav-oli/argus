# Epic 1 — Hardening Backlog

Real findings from the code review of the Epic 1 skeleton stories (1.4–1.6) that
are **out of scope for the skeleton** and were deliberately deferred. All six ACs
of each story passed; these are robustness/security items to pick up when the
relevant feature/hardening work lands. Captured here so they aren't lost.

## From Story 1.4 — Model Gateway
- **No call/acquire timeout.** `DefaultModelGateway` uses `permits.acquire()` (unbounded) and the Ollama call has no timeout, so a hung/slow big-model call holds the single (concurrency=1) permit and starves all queued callers. Add `tryAcquire(timeout)` + a model-call timeout + fail-fast when the gateway gets real load. _(Owner: Model Gateway hardening / when Ask-AI + agents drive real traffic.)_
- **Fallback masks all `RuntimeException`s into a stub string.** Today the Haiku fallback is a stub, so any model error returns a placeholder the caller can't distinguish from a real answer. Revisit the error contract (`ModelGatewayException` vs fallback) when the real Anthropic Haiku fallback is implemented.
- **No `@Min`/validation on `concurrency`** (0/negative bricks the gateway) and **no null/empty/oversized prompt validation**. Cheap guards to add with the first real caller.

## From Story 1.5 — Agent Runtime + Redis Streams
- **Crash recovery / PEL reclaim.** The runtime reads only `ReadOffset.lastConsumed()` (new messages) — by design for the skeleton (the AC is "left pending, no redelivery loop"). A delivered-but-unacked message after a crash is never reclaimed. Add `XPENDING`/`XAUTOCLAIM`-based recovery + idempotency (dedupe by `eventId`) + retry/DLQ. _(Owner: agent reliability / Degraded Mode, per architecture's deferred-retry posture.)_
- **Serial dispatch.** A single `@Scheduled` tick processes all agents and their records serially, so one slow `handle` blocks every other agent. Parallelize per-agent (virtual-thread-per-agent) or isolate with a timeout when agent count/throughput grows.
- **Unbounded stream growth.** `XADD` has no `MAXLEN`/`MINID` cap and there's no scheduled trim — streams grow forever on a 24/7 Mini. Add capped `XADD` or a trim job.
- **Envelope `version` not validated** against `ENVELOPE_VERSION` (no schema-evolution path); poison/garbage records sit pending. Add version checks + a poison/quarantine path when event schemas evolve.

## From Story 1.6 — REST + WebSocket
- **WebSocket origin is wide open** (`setAllowedOriginPatterns("*")`). Restrict to the allowed app origin(s). A naive restriction would break the single-origin Tailscale deploy (the tailnet host must be allowed), so this needs **env-configurable allowed origins shared by CORS + WS** — best done **with Epic 2 (Security)**, where WS authorization/session also lands. _(Owner: Epic 2.)_
- **`wsClient` robustness.** `ready` never rejects on transport failure (callers can deadlock); `onConnect` re-subscribes on reconnect without `unsubscribe()` (subscription leak). Wire `onWebSocketError`/`onWebSocketClose` + a connect timeout, and clean up the prior subscription on reconnect. _(The unguarded `JSON.parse` was already fixed in review.)_
- **`apiClient` empty-body / content-type.** `res.json()` is called unconditionally on 2xx (throws on 204/empty) and the error path parses any body as `problem+json` without a content-type check. Add guards when non-GET/204 endpoints appear.
- **Demo payload contract.** The 1.6 STOMP demo publishes a raw `String` while `wsClient` expects JSON — align the real push payloads to JSON (typed) as live topics are built.

_All items above are tracked; none block Epic 1 completion. Hardware-only validation lives in `docs/mac-mini-validation.md`._
