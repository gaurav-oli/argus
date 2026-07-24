# Epic 1 — Hardening Backlog

Real findings from the code review of the Epic 1 skeleton stories (1.4–1.6) that
are **out of scope for the skeleton** and were deliberately deferred. All six ACs
of each story passed; these are robustness/security items to pick up when the
relevant feature/hardening work lands. Captured here so they aren't lost.

## From Story 1.4 — Model Gateway
- ~~**No call/acquire timeout.**~~ — **FIXED 2026-07-23.** `permits.acquire()` (unbounded) is now
  `permits.tryAcquire(callTimeout)`, and the model call itself is raced against the same
  configurable timeout (`argus.model.call-timeout-seconds`, default 150s — comfortably above the
  ~115s worst case observed live on the Mini, see `docs/mac-mini-validation.md` §3/§6) via a bounded
  `CompletableFuture`. Either timeout now falls through to the Haiku fallback instead of blocking
  the caller (and everyone queued behind them) forever. Verified: a unit test
  (`generateFallsBackToHaikuWhenModelCallHangsPastTheTimeout`) proves the caller gets an answer near
  the configured timeout even when the underlying model call hangs indefinitely, and that the permit
  is released despite the call itself still running in the background; a live functional check
  against the deployed backend confirmed the golden (successful, non-timeout) path is unaffected
  (2.0s round trip, correct grounded answer). The abandoned background call on a timeout isn't
  forcibly cancelled (Spring AI's synchronous `ChatClient` has no cancel hook) — documented as an
  accepted gap in `DefaultModelGateway`'s Javadoc.
- ~~**Fallback masks all `RuntimeException`s into a stub string.**~~ — **FIXED 2026-07-24.** The real
  `AnthropicHaikuFallback` was already in place, but revisiting `generateBig()`'s error contract
  surfaced a genuine bug it created: every `haikuFallback.generate(prompt)` call (permit-timeout,
  primary-failure, blank-content) sat *inside* the same `try` whose `catch (RuntimeException ex)`
  was meant only for the primary model. A real Haiku failure (bad key, Anthropic outage, rate-limit)
  got re-caught by that same clause, mislabeled in the logs as `"Primary model failed"`, and silently
  retried against Haiku a **second time** — double billing, double latency, and the real failure only
  surfaced if both calls failed. `generateBig()` is restructured so the permit is released and each
  fallback call happens strictly outside any try/catch that could recapture it — a Haiku failure now
  propagates as its own `ModelGatewayException` (→ 503) on the first and only attempt. Verified with
  3 new unit tests (`haikuFailureAfterPrimaryFailureIsNotRetriedAndPropagates`,
  `haikuFailureAfterBlankPrimaryResponseIsNotRetriedAndPropagates`,
  `permitIsReleasedEvenWhenHaikuFallbackFails`) plus the full 378-test backend suite, all green.
  Live-validated against the deployed backend: a normal ask (golden path, 69s local-model round trip)
  and a direct Haiku escalation (2.0s) both returned correct grounded answers; a real production
  blank-response-from-the-local-model event was also observed live (129s local timeout → single
  correctly-attributed Haiku fallback call, real answer returned, cost recorded once) — direct
  confirmation the exact bug this fix closes doesn't recur in practice.
- ~~**No `@Min`/validation on `concurrency`** (0/negative bricks the gateway) and **no null/empty/oversized prompt validation**.~~ — **FIXED 2026-07-23.** `DefaultModelGateway`'s constructor now throws `IllegalArgumentException` for `concurrency < 1` (fails loudly at startup instead of silently bricking every BIG-tier call), backed by a unit test; `ModelGatewayProperties.concurrency` also carries `@Min(1)` for documentation/discoverability. `generate()`/`escalate()` now reject a null/blank prompt (`BadRequestException` → 400) or one over 50,000 characters (`PayloadTooLargeException` → 413) before it ever reaches the semaphore or Haiku — both reusing the app's existing exception types rather than inventing new ones.

## From Story 1.5 — Agent Runtime + Redis Streams
All four items below — **FIXED 2026-07-24**, implemented together in `AgentRuntime`/`AgentProperties`/`AgentEventPublisher` since they share the same dispatch path:

- ~~**Crash recovery / PEL reclaim.**~~ `reclaimPending()` now runs every tick, reclaiming (XCLAIM) any message idle past `argus.agent.pel-reclaim-idle-ms` (default 5 minutes — deliberately ~2x the Model Gateway's own 150s call-timeout ceiling, so a handler legitimately still running a slow model call is never mistaken for stuck) under the same consumer identity (`agent.name()` is stable across restarts) and redispatches it through the normal path. Capped at `argus.agent.max-delivery-attempts` (default 5, using Redis's own XPENDING delivery count) — **this cap was added after a real bug my own test suite caught**: the first version had no cap, so a permanently-failing message (poison record, version mismatch) got reclaimed and redispatched on essentially every tick forever, hammering Redis and the handler indefinitely. Also fixed a related log-spam issue: the "giving up" log for an exhausted message now fires once, not every tick thereafter.
- ~~**Serial dispatch.**~~ Each agent's poll now runs on its own virtual thread within a tick (`Executors.newVirtualThreadPerTaskExecutor()` — deliberately unbounded, not a fixed pool; see the agent-fleet-scheduler memory note on why a bounded pool previously starved the fleet), so one slow agent no longer blocks the others in iteration order. `fixedDelay` still guarantees ticks themselves never overlap.
- ~~**Unbounded stream growth.**~~ `AgentEventPublisher.publish()` now XADDs with `MAXLEN ~ 10000` (approximate trimming).
- ~~**Envelope `version` not validated.**~~ `dispatch()` now rejects (logs + leaves pending, same "quarantined for manual inspection" convention as an undecodable record) any envelope whose `version` doesn't match `AgentEventPublisher.ENVELOPE_VERSION`.
- **Idempotency (dedupe by `eventId`)** was added as part of this same pass, beyond what was originally scoped here: a Redis key (`argus:agent:{name}:dedup:{eventId}`, TTL `argus.agent.dedupe-ttl-hours`, default 24h) marked after a successful `handle()` — not before, so a crash mid-handling still gets fully retried by the reclaim path — skips re-running side effects if a reclaim races a slow-but-successful original handler.
- ~~**Retry/DLQ beyond the delivery-attempt cap.**~~ — **FIXED 2026-07-24.** An exhausted message
  (poison/undecodable record, unsupported envelope version, or a handler bug that always throws — all
  three converge on the same exhausted-delivery-count path) no longer stays pending forever. Once
  `reclaimPending()` sees `totalDeliveryCount >= maxDeliveryAttempts`, it claims the message one last
  time, writes it (with failure metadata: original stream, agent, delivery-attempt count,
  dead-lettered-at timestamp) onto a per-agent dead-letter stream (`{streamKey}:dlq`, same `MAXLEN ~
  10000` approximate-trim convention as `AgentEventPublisher`), and acknowledges it off the original
  stream — freeing the PEL while leaving a durable, inspectable record instead of an invisible
  permanently-pending entry. No automated retry-from-DLQ exists (out of scope — nothing here can know
  whether the root cause was actually fixed); requeuing a DLQ entry back onto its source stream
  remains a manual/operator action.

Verified: unit tests (delivery-cap logic) + 6 real-Redis Testcontainers integration tests
(`AgentRuntimeIntegrationTest` — normal dispatch, handler-failure-leaves-pending,
crash-recovery-reclaim-and-retry, dedup-skips-a-redelivery, unsupported-version-is-quarantined,
**exhausted-message-is-dead-lettered-and-acknowledged**) + the full 379-test backend suite, all green.
Live-validated post-deploy: consumer-group health checked across all 4 production streams
(`XINFO GROUPS`) showed 0 pending everywhere and real ingestion activity continuing normally, no
regressions. The specific dead-letter code path itself was proven against real Redis via the new
integration test rather than a live production exhaustion event — the previously-orphaned messages
this same session's earlier Agent Runtime fix found (28+ days pending, 53 backlog entries) had
already fully drained by the time this fix deployed, so there was nothing left in production actually
sitting at the exhaustion threshold to observe live; forcing one would require waiting out prod's
5-minute `pel-reclaim-idle-ms` five times over (~25 minutes) for no material additional confidence
beyond the real-Redis integration test.

## From Story 1.6 — REST + WebSocket
- ~~**WebSocket origin is wide open**~~ — **FIXED 2026-07-23.** `WebSocketConfig` now reuses `WebProperties.allowedOrigins()` (`argus.web.allowed-origins` / `ARGUS_WEB_ALLOWED_ORIGINS`), the same env-configurable list `CorsConfig` already validates against, instead of `setAllowedOriginPatterns("*")`. Verified both directions: `StompRoundTripIntegrationTest#handshakeFromDisallowedOriginIsRejected` (new) confirms a forged `Origin` header is rejected; the real production frontend's WS connection (`wss://leannas-mac-mini.taila43287.ts.net/ws`) was confirmed still working live post-deploy via a real browser session (frame received, zero console errors).
- ~~**`wsClient` robustness.**~~ — **FIXED 2026-07-23.** `ready` now rejects (instead of hanging forever) on `onStompError`, `onWebSocketError`, `onWebSocketClose`, or a 15s connect timeout (`CONNECT_TIMEOUT_MS`); `onConnect` calls `subscription?.unsubscribe()` before re-subscribing on reconnect, closing the leak. Verified against the real `@stomp/stompjs` library (not a mock): a genuinely unreachable address rejected in 27ms via `onWebSocketError`, and a black-holed address (`192.0.2.1`, TEST-NET-1) correctly rejected at the 3000ms timeout boundary. Live-validated post-deploy via a real Playwright browser session against the production frontend — the deployed bundle opened `wss://leannas-mac-mini.taila43287.ts.net/ws`, received a live frame, and stayed connected with zero console errors.
- ~~**`apiClient` empty-body / content-type.**~~ — **FIXED 2026-07-23.** `apiGet` was the one remaining gap (`apiPost`/`apiPut`/DELETE call sites already guarded); it now checks for a 204 status or `content-length: 0` before parsing, matching the pattern the other verbs already used. Verified against a real Node HTTP server: confirmed the fix returns `undefined` for both a 204 and a 200-with-empty-body response without throwing, parses a normal JSON body correctly, and reproduced the pre-fix regression (`Unexpected end of JSON input`) to prove this is a genuine fix. The error-path `problem+json` parsing already had try/catch protection against non-JSON bodies, so no change was needed there.
- **Demo payload contract.** The 1.6 STOMP demo publishes a raw `String` while `wsClient` expects JSON — align the real push payloads to JSON (typed) as live topics are built.

_All items above are tracked; none block Epic 1 completion. Hardware-only validation lives in `docs/mac-mini-validation.md`._
