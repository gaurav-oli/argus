# Finnhub resilience & fallback (Story 4.5, GAP-4)

Finnhub is the linchpin external API (news, prices, FX, earnings). Its free tier caps at **60
REST calls/minute**, so Agent 1's per-ticker news fetches must be throttled and must degrade — never
break — when the limit is approached. GAP-4 is mitigated by a Resilience4j rate limiter + retry on
all Finnhub REST access, plus a documented (currently stubbed) fallback toggle.

## How it works

All Finnhub REST calls go through `com.argus.marketdata.FinnhubRest`:

- **Rate limiter** — `limitForPeriod` calls per `refreshPeriodSeconds` window (default 60/60s). A call
  that can't get a permit within `acquireTimeoutSeconds` is **dropped** (returns empty), not queued
  indefinitely.
- **Retry with exponential backoff** — transient failures (HTTP 429, 5xx, network I/O) are retried up
  to `maxAttempts` times, backing off from `initialBackoffMs` by `backoffMultiplier`. Permanent 4xx
  (bad symbol/auth) are **not** retried.
- **Graceful degradation** — when a call is dropped or exhausts retries, `FinnhubRest.get` returns
  `Optional.empty()`. `FinnhubNewsSource` treats that as "no articles this cycle", so ingestion falls
  back to the free keyless sources (**GDELT/RSS**) instead of failing.

## Configuration (`argus.finnhub.resilience.*`)

| Property | Default | Meaning |
|---|---|---|
| `limit-for-period` | 60 | calls per window (free-tier cap) |
| `refresh-period-seconds` | 60 | rate-limit window length |
| `acquire-timeout-seconds` | 5 | max wait for a permit before dropping |
| `max-attempts` | 3 | total attempts per call |
| `initial-backoff-ms` | 500 | first retry backoff (doubles each retry) |
| `backoff-multiplier` | 2.0 | exponential backoff factor |
| `fallback-provider` | `none` | `none` \| `alpha_vantage` \| `yahoo` |

## Fallback toggle (stub)

`fallback-provider` selects an alternate data provider for when Finnhub is unavailable. The toggle and
its seam (`com.argus.marketdata.FinnhubFallback`) are wired now; **the Alpha Vantage / Yahoo providers
are not yet implemented**. Selecting a provider logs a warning and is otherwise a no-op until the
implementation lands — the toggle can be flipped ahead of that work. For news specifically, GDELT/RSS
already provide a free fallback path, so `none` is the safe default.
