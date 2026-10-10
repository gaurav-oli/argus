// Typed REST client for the Argus backend. Success returns the resource directly;
// errors are RFC 9457 problem+json, parsed into a typed ApiError.
//
// `credentials: "include"` sends the HttpOnly ARGUS_SESSION cookie (Story 2.1) on every
// call. On the Mini this is single-origin; in local dev it's cross-port, which the backend
// CORS config allows (allowCredentials + explicit origin).

import { reportFailure, reportOk } from "./refreshHealth";

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Mirrors the backend `SystemInfo` record. */
export interface SystemInfo {
  name: string;
  version: string;
  profile: string;
  time: string;
}

/** The signed-in Google account — never the raw Google subject id. */
export interface AuthUser {
  name: string;
  email: string;
  pictureUrl: string | null;
  admin: boolean;
}

/** Mirrors the backend `AuthStatus` record. `user` is null when not signed in with Google. */
export interface AuthStatus {
  authenticated: boolean;
  user: AuthUser | null;
}

/** RFC 9457 Problem Details body. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail, status: number) {
    super(problem.detail ?? problem.title ?? `Request failed (${status})`);
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

// Global 401 handler: the AuthGate registers a callback so that whenever any request finds the
// session expired (e.g. the Story 2.3 idle timeout elapsed), the app re-gates to the lock screen.
// Re-gating unmounts the shell + PrivacyProvider, which also resets tap-to-reveal (FR-36 / 2.4).
let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail = { status: res.status, title: res.statusText };
  try {
    problem = (await res.json()) as ProblemDetail;
  } catch {
    // non-JSON error body — keep the status/statusText fallback
  }
  if (res.status === 401) {
    onUnauthorized?.();
  }
  return new ApiError(problem, res.status);
}

export async function apiGet<T>(path: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      headers: { Accept: "application/json" },
      credentials: "include",
    });
  } catch (err) {
    reportFailure(null); // S-D3: offline / backend down — the page is now showing stale data
    throw err;
  }
  if (!res.ok) {
    reportFailure(res.status);
    throw await toApiError(res);
  }
  reportOk();
  // A 204 or otherwise empty body would throw inside res.json() (Epic 1 hardening backlog —
  // Story 1.6). No current GET endpoint returns one, but apiGet is the shared path for nearly
  // every read in this file, so guarding it here — matching the pattern apiPost/apiPut already
  // use — is cheap insurance against a future endpoint that legitimately has nothing to return.
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * POST JSON. Returns the parsed body for 2xx responses that have one, or `undefined`
 * for empty/204/201 responses. Throws {@link ApiError} on non-2xx.
 */
export async function apiPost<T = void>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });
  if (!res.ok) {
    throw await toApiError(res);
  }
  if (res.status === 204 || res.headers.get("content-length") === "0") {
    return undefined as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const getSystemInfo = (): Promise<SystemInfo> =>
  apiGet<SystemInfo>("/api/system-info");

// ---- Auth (Story 2.1) ----

export const getAuthStatus = (): Promise<AuthStatus> =>
  apiGet<AuthStatus>("/api/auth/status");

/** Starts Google Sign-In — a plain browser navigation, not a fetch (the backend replies with a 302
 * to Google's consent screen), so callers set `window.location.href` (or an `<a href>`) to this.
 * Uses the full backend origin, not a bare relative path — needed in local dev where the frontend
 * and backend are cross-origin; same-origin on the Mini, where BASE_URL is empty. */
export const googleSignInUrl = (): string => `${BASE_URL}/api/auth/google/login`;

// ---- Admin usage stats (multi-user) ----

/** Mirrors the backend `AdminController.UserStatsView` — engagement only, never portfolio data. */
export interface AdminUserStats {
  name: string;
  email: string;
  pictureUrl: string | null;
  admin: boolean;
  joinedAt: string;
  lastLoginAt: string | null;
  loginCount: number;
  activeDays: number;
  totalActiveMinutes: number;
  lastActiveAt: string | null;
  /** Set while the admin has revoked this person's access (their data is kept). */
  revokedAt: string | null;
}

/** 403 (ApiError) for a non-admin — the caller should already know not to show this to one. */
export const getAdminUserStats = (): Promise<AdminUserStats[]> =>
  apiGet<AdminUserStats[]>("/api/admin/users");

/** Mirrors the backend `AdminController.InviteView`. `emailSentAt`/`openedAt` are null until the
 * admin actually sends the email / the person visits the link; `joined` is the one that matters. */
export interface AdminInvite {
  email: string;
  invitedAt: string;
  joined: boolean;
  emailSentAt: string | null;
  openedAt: string | null;
  /** They joined, then the admin revoked their access. */
  revoked: boolean;
}

export const getAdminInvites = (): Promise<AdminInvite[]> =>
  apiGet<AdminInvite[]>("/api/admin/invites");

/** Allow a new email to sign in with Google. Idempotent — inviting an already-invited email is fine. */
export const inviteFriend = (email: string): Promise<AdminInvite> =>
  apiPost<AdminInvite>("/api/admin/invites", { email });

/** Actually email the invite (Resend) — throws (ApiError, 503) if sending isn't configured or fails. */
export const sendInviteEmail = (email: string): Promise<AdminInvite> =>
  apiPost<AdminInvite>("/api/admin/invites/send", { email });

/** Lock someone out (sign-in refused, all sessions ended) but keep their data. Reversible. */
export const revokeUser = (email: string): Promise<AdminUserStats> =>
  apiPost<AdminUserStats>("/api/admin/users/revoke", { email });

/** Give a revoked person their access back. */
export const restoreUser = (email: string): Promise<AdminUserStats> =>
  apiPost<AdminUserStats>("/api/admin/users/restore", { email });

/** Permanently delete someone's account, all their private data and their invite. Not reversible. */
export const deleteUser = (email: string): Promise<void> => apiPost("/api/admin/users/delete", { email });

/** Withdraw an invite nobody has used yet (409 if they already joined). */
export const removeInvite = (email: string): Promise<void> => apiPost("/api/admin/invites/remove", { email });

/** Best-effort: tell the backend an invite link was opened, before the person has even signed in.
 * Never throws into the caller — a failed beacon shouldn't block showing the sign-in screen. */
export async function markInviteOpened(token: string): Promise<void> {
  try {
    await fetch(`${BASE_URL}/api/invite/open?token=${encodeURIComponent(token)}`, {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // best-effort — see above
  }
}

export const logout = (): Promise<void> => apiPost("/api/auth/logout");

// ---- Settings (Story 2.3) ----

/** Session idle timeout in seconds; null/absent = Never. Mirrors the backend record. */
export interface SessionTimeout {
  seconds: number | null;
}

async function apiPut<T = void>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw await toApiError(res);
  if (res.status === 204 || res.headers.get("content-length") === "0") return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const getSessionTimeout = (): Promise<SessionTimeout> =>
  apiGet<SessionTimeout>("/api/settings/session-timeout");

export const setSessionTimeout = (seconds: number | null): Promise<void> =>
  apiPut("/api/settings/session-timeout", { seconds });

/** Demo Mode: hide the Portfolio section and mask $ amounts/holdings everywhere else. */
export interface DemoMode {
  demoMode: boolean;
}

export const getDemoMode = (): Promise<DemoMode> => apiGet<DemoMode>("/api/settings/demo-mode");

export const setDemoMode = (demoMode: boolean): Promise<DemoMode> =>
  apiPut<DemoMode>("/api/settings/demo-mode", { demoMode });


// ---- Active sessions / remote kill (Story 2.7) ----

/** Mirrors the backend `SessionStore.SessionInfo` record. */
export interface SessionInfo {
  handle: string;
  device: string;
  createdAt: string | null;
  lastActiveAt: string | null;
  current: boolean;
}

export const listSessions = (): Promise<SessionInfo[]> =>
  apiGet<SessionInfo[]>("/api/auth/sessions");

export async function revokeSession(handle: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/auth/sessions/${encodeURIComponent(handle)}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) throw await toApiError(res);
}

// ---- Portfolio PDF import (Story 3.1, FR-1) ----

/** One holding parsed from a statement. A field the parser couldn't read is `null` and named in
 *  `issues`, with `needsReview` set — flagged for manual entry, never dropped. */
export interface ParsedHolding {
  ticker: string;
  companyName: string | null;
  shares: number | null;
  costBasis: number | null;
  costBasisCurrency: string;
  acquisitionDate: string | null;
  account: string | null;
  needsReview: boolean;
  issues: string[];
}

/** Mirrors the backend `ImportPreview` record — a staged, not-yet-persisted upload. */
export interface ImportPreview {
  importId: number;
  filename: string;
  status: string;
  message: string | null;
  holdings: ParsedHolding[];
}

/** Mirrors the backend `PositionView` record — a persisted holding (Stories 3.1 + 3.2). */
export interface Position {
  id: number;
  ticker: string;
  companyName: string | null;
  shares: number | null;
  costBasis: number | null;
  costBasisCurrency: string;
  /** Weighted-average CAD ACB at purchase-time FX (Story 3.2); null when not yet computable. */
  cadAcb: number | null;
  /** True when the purchase FX is an unconfirmed estimate. */
  fxEstimated: boolean;
  acquisitionDate: string | null;
  needsReview: boolean;
  source: string;
}

/** Mirrors the backend `ImportAccepted` record — an automatic (`mode=auto`) upload queued on the
 *  background runner; the actual result arrives later as a push notification + email, not here. */
export interface ImportAccepted {
  status: string;
  message: string;
}

/** Upload a brokerage statement PDF; returns the parsed preview (nothing is persisted yet). Only for
 *  the explicit {@code mode: "heuristic" | "llm"} synchronous paths — see {@link uploadStatementAuto}
 *  for the default automatic (local-Gemma, self-verifying) path the Import Statement screen uses. */
export async function uploadStatement(
  file: File,
  opts?: { mode?: "heuristic" | "llm"; institution?: string },
): Promise<ImportPreview> {
  const form = new FormData();
  form.append("file", file);
  const params = new URLSearchParams();
  if (opts?.mode) params.set("mode", opts.mode);
  if (opts?.institution) params.set("institution", opts.institution);
  const query = params.toString() ? `?${params.toString()}` : "";
  // No explicit Content-Type — the browser sets multipart/form-data with the boundary.
  const res = await fetch(`${BASE_URL}/api/portfolio/imports${query}`, {
    method: "POST",
    credentials: "include",
    headers: { Accept: "application/json" },
    body: form,
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as ImportPreview;
}

/**
 * Upload a statement PDF for AUTOMATIC background processing: tries local Gemma with a
 * self-verification loop (falling back to Claude only as a last resort), then either applies it to
 * the portfolio directly or leaves it staged for review — never fails silently. Returns immediately
 * with just an acknowledgement; the outcome arrives later as a push notification and an email.
 */
export async function uploadStatementAuto(file: File, institution?: string): Promise<ImportAccepted> {
  const form = new FormData();
  form.append("file", file);
  const params = new URLSearchParams({ mode: "auto" });
  if (institution) params.set("institution", institution);
  const res = await fetch(`${BASE_URL}/api/portfolio/imports?${params.toString()}`, {
    method: "POST",
    credentials: "include",
    headers: { Accept: "application/json" },
    body: form,
  });
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as ImportAccepted;
}

/** Commit a staged import's holdings into the portfolio. */
export const confirmImport = (importId: number): Promise<Position[]> =>
  apiPost<Position[]>(`/api/portfolio/imports/${importId}/confirm`);

/** Statements the automatic path staged but wasn't confident enough to auto-apply — still awaiting
 *  a manual look, so an uncertain import is never just lost after its "please review" notification. */
export const listPendingImports = (): Promise<ImportPreview[]> =>
  apiGet<ImportPreview[]>("/api/portfolio/imports/pending");

export const listPositions = (): Promise<Position[]> =>
  apiGet<Position[]>("/api/portfolio/positions");

// ---- Live portfolio value (Story 3.4, FR-2) ----

/** Mirrors the backend `PositionValue` record; `price`/`marketValue` null until a tick arrives,
 *  `dayPnl`/`previousClose` null until a previous close is known. */
export interface PositionValue {
  ticker: string;
  companyName: string | null;
  shares: number | null;
  price: number | null;
  marketValue: number | null;
  costBasis: number | null;
  totalPnl: number | null;
  totalPnlPercent: number | null;
  previousClose: number | null;
  dayPnl: number | null;
  dayPnlPercent: number | null;
  currency: string;
  cadMarketValue: number | null;
  cadPnl: number | null;
  weightPercent: number | null;
  afterHours: boolean;
  asOf: string | null;
  institution: string | null;
  account: string | null;
  id: number;
  usdMarketValue: number | null;
  cadAcb: number | null;
  fxEstimated: boolean;
  /** Friendly account name derived from the label, e.g. "RRSP (USD: WQD7)" / "Cash Account (CAD: WK3A)". */
  accountName: string | null;
  /** The account's own currency ("CAD" | "USD"). */
  accountCurrency: string | null;
  /** "Joint" | "Solo" | "Corporate" (null when unknown). */
  ownerType: string | null;
  /** Holder name(s), e.g. "Gaurav Oli & Varsha Gupta" or "Gaurav Oli". */
  ownerName: string | null;
  /** Normalized registration type for cross-bank grouping: "TFSA" | "RRSP" | "RESP" | "Cash" | "Corporate" | … (null when unknown). */
  accountType: string | null;
}

/** Mirrors the backend `PortfolioSnapshot` record — totals in CAD (plus a USD equivalent). Live on `/topic/portfolio`. */
export interface PortfolioSnapshot {
  totalValueCad: number | null;
  totalCostCad: number | null;
  totalPnlCad: number | null;
  totalValueUsd: number | null;
  anyAfterHours: boolean;
  asOf: string;
  positions: PositionValue[];
}

export const getPortfolioValue = (): Promise<PortfolioSnapshot> =>
  apiGet<PortfolioSnapshot>("/api/portfolio/value");

/**
 * Does Argus currently think a stock you hold is a genuine long-term compounder worth continuing to
 * accumulate, or should it be reconsidered? Reuses the same recommendation/deep-analysis data shown
 * elsewhere, reframed against what you actually own — never a new model call. `reason`/`timingNote`
 * are null only for NOT_ENOUGH_DATA (Argus won't force a confident call on thin evidence).
 */
export interface HoldingOutlook {
  positionId: number;
  ticker: string;
  outlook: "KEEP" | "RECONSIDER" | "NOT_ENOUGH_DATA";
  outlookLabel: string;
  reason: string | null;
  /** The existing chart-derived buy note, reused as a qualitative "good time to add, or wait" steer —
   * never a fabricated number. */
  timingNote: string | null;
  registeredAccount: boolean;
  /** Set only when `registeredAccount` — the CRA "business income" caution for active trading there. */
  registeredAccountNote: string | null;
  /** A contextual reminder to log a change, only for a long-held "keep" position that's gone stale. */
  updateNudge: string | null;
}

export const getHoldingOutlooks = (): Promise<HoldingOutlook[]> =>
  apiGet<HoldingOutlook[]>("/api/portfolio/outlook");

/** Uninvested cash per account+currency, folded into the portfolio total. */
export interface CashBalanceView {
  id: number;
  account: string;
  currency: string;
  amount: number;
  /** Friendly account name, e.g. "Cash Account (USD: WK3B)". */
  accountName: string | null;
  /** "Joint" | "Solo" (null when unknown). */
  ownerType: string | null;
  /** Holder name(s). */
  ownerName: string | null;
}

export const getCash = (): Promise<CashBalanceView[]> => apiGet<CashBalanceView[]>("/api/portfolio/cash");

/** Set the cash for an account+currency. Amount 0 removes it. */
export const setCash = (account: string, currency: string, amount: number): Promise<void> =>
  apiPut("/api/portfolio/cash", { account, currency, amount });

/** One point in the portfolio value chart series (Story 3.6). `date` is an ISO date string. */
export interface ValuePoint {
  date: string;
  totalValueCad: number;
}

export const getValueHistory = (range: string): Promise<ValuePoint[]> =>
  apiGet<ValuePoint[]>(`/api/portfolio/value-history?range=${encodeURIComponent(range)}`);

// ---- Manual position edit (Story 3.7, FR-5) ----

/** Mirrors the backend `AuditEntry` record. */
export interface AuditEntry {
  id: number;
  ticker: string;
  action: string;
  detail: string | null;
  createdAt: string;
}

export interface AddPositionBody {
  ticker: string;
  companyName?: string;
  shares: number;
  costBasis: number;
  currency: string;
  acquisitionDate?: string;
}

export interface EditPositionBody {
  companyName?: string;
  ticker?: string;
  shares?: number;
  costBasis?: number;
  currency?: string;
  acquisitionDate?: string;
}

export const addPosition = (body: AddPositionBody): Promise<Position> =>
  apiPost<Position>("/api/portfolio/positions", body);

export const editPosition = (id: number, body: EditPositionBody): Promise<Position> =>
  apiPut<Position>(`/api/portfolio/positions/${id}`, body);

export async function removePosition(id: number): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/portfolio/positions/${id}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) throw await toApiError(res);
}

export const listAudit = (): Promise<AuditEntry[]> => apiGet<AuditEntry[]>("/api/portfolio/audit");

// ---- Health score (Story 3.8/3.9, FR-6/FR-7) ----

/** Mirrors the backend `HealthDeduction` record. */
export interface HealthDeduction {
  code: string;
  label: string;
  points: number;
  reason: string;
  suggestion: string;
}

/** Mirrors the backend `HealthScoreResult` record. */
export interface HealthScoreResult {
  score: number;
  deductions: HealthDeduction[];
  computedAt: string;
}

export const getHealthScore = (): Promise<HealthScoreResult> =>
  apiGet<HealthScoreResult>("/api/portfolio/health-score");

/** One point in the Health Score trend (Story 3.9). `date` is an ISO date string. */
export interface HealthPoint {
  date: string;
  score: number;
}

export const getHealthScoreHistory = (days = 30): Promise<HealthPoint[]> =>
  apiGet<HealthPoint[]>(`/api/portfolio/health-score/history?days=${days}`);

/** Confirm/override a position's purchase FX (Story 3.2): supply a rate, or a date to look one up. */
export const confirmPositionFx = (
  id: number,
  body: { rate?: number; date?: string },
): Promise<Position> => apiPut<Position>(`/api/portfolio/positions/${id}/fx`, body);

// ---- Corporate actions (Story 3.3, FR-1c) ----

/** Mirrors the backend `CorporateActionView` record. */
export interface CorporateAction {
  id: number;
  ticker: string;
  positionId: number | null;
  type: string;
  ratio: number | null;
  newTicker: string | null;
  exDate: string | null;
  /** pending | applied | dismissed */
  status: string;
  note: string | null;
  source: string;
  createdAt: string;
  appliedAt: string | null;
}

export const listCorporateActions = (): Promise<CorporateAction[]> =>
  apiGet<CorporateAction[]>("/api/portfolio/corporate-actions");

export const recordCorporateAction = (body: {
  ticker: string;
  type: string;
  ratio?: number;
  newTicker?: string;
  exDate?: string;
}): Promise<CorporateAction> =>
  apiPost<CorporateAction>("/api/portfolio/corporate-actions", body);

export const confirmCorporateAction = (id: number): Promise<CorporateAction> =>
  apiPost<CorporateAction>(`/api/portfolio/corporate-actions/${id}/confirm`);

export const dismissCorporateAction = (id: number): Promise<CorporateAction> =>
  apiPost<CorporateAction>(`/api/portfolio/corporate-actions/${id}/dismiss`);

// ---- Intelligence / Agent 1 (Epic 4) ----

export type SentimentLabel = "BULLISH" | "BEARISH" | "NEUTRAL";

/** A news article with Agent-1 sentiment/relevance (null scores = not yet analyzed). */
export interface NewsItem {
  id: number;
  source: string;
  headline: string;
  url: string | null;
  publishedAt: string;
  tickers: string[];
  sentimentLabel: SentimentLabel | null;
  sentimentScore: number | null;
  relevanceScore: number | null;
  analyzed: boolean;
}

/** A source's credibility score, tier band (PLATINUM…BLOCKED), and block state (Story 4.3). */
export interface SourceCredibilityItem {
  source: string;
  score: number;
  tier: string;
  blocked: boolean;
  correctCount: number;
  incorrectCount: number;
}

/** A flagged "stranger" ticker under heavy coverage with its pump-and-dump risk (Story 4.4). */
export interface StrangerAlertItem {
  ticker: string;
  riskScore: number;
  coverageCount: number;
  distinctSources: number;
  avgSourceScore: number | null;
  requiredConsensus: number;
  windowStart: string;
}

export const getNewsFeed = (): Promise<NewsItem[]> =>
  apiGet<NewsItem[]>("/api/intelligence/news");

export const getSourceCredibility = (): Promise<SourceCredibilityItem[]> =>
  apiGet<SourceCredibilityItem[]>("/api/intelligence/sources");

export const getStrangerAlerts = (): Promise<StrangerAlertItem[]> =>
  apiGet<StrangerAlertItem[]>("/api/intelligence/strangers");

/** Mirrors `IntelligenceController.BreakingItem` — a breaking-news alert that fired a push, with a
 * Gemma-written summary once curation has run (`summary` is empty until then). */
export interface BreakingAlertItem {
  id: number;
  headline: string;
  url: string | null;
  tickers: string[];
  reason: string;
  sentimentLabel: string | null;
  summary: string;
  createdAt: string;
}

/** Mirrors `IntelligenceController.BreakingQueue`. */
export interface BreakingQueue {
  /** Ready-to-read, non-duplicate, unread alerts, most recent first. */
  alerts: BreakingAlertItem[];
  /** Alerts still being curated (deduped/summarized) in the background. */
  pending: number;
}

export const getBreakingAlerts = (): Promise<BreakingQueue> =>
  apiGet<BreakingQueue>("/api/intelligence/breaking");

/** Mark a breaking alert read (soft dismiss — the audit history is kept server-side). */
export const markBreakingDone = (id: number): Promise<BreakingQueue> =>
  apiPost<BreakingQueue>(`/api/intelligence/breaking/${id}/done`);

// ---- Economic calendar / Agent 7 (Epic 5) ----

/**
 * A calendar event — upcoming (`daysUntil` >= 0) or recently reported (`daysUntil` < 0, last 30
 * days by default), latest to oldest by event date. `quietPeriod` (CLEAR|NOTE|QUIET) is set only
 * for upcoming earnings (Story 5.3). `epsActual`/`epsEstimate`/`epsSurprisePercent` are set only
 * once an earnings event has reported. `logoUrl` is null when nothing's cached for the ticker (or
 * the event has no ticker, e.g. a Fed event).
 */
export interface UpcomingEvent {
  id: number;
  type: string;
  ticker: string | null;
  title: string;
  eventDate: string;
  daysUntil: number;
  quietPeriod: string | null;
  epsActual: number | null;
  epsEstimate: number | null;
  epsSurprisePercent: number | null;
  logoUrl: string | null;
}

export const getUpcomingEvents = (): Promise<UpcomingEvent[]> =>
  apiGet<UpcomingEvent[]>("/api/calendar/upcoming");

/**
 * Batch ticker -> logo URL lookup (misses simply omitted from the response — the caller falls
 * back to an initial-letter icon). Backed by the same cache Agent 7's ingest warms, so it never
 * triggers a fresh external fetch on the read path.
 */
export const getCompanyLogos = (tickers: string[]): Promise<Record<string, string>> => {
  const distinct = [...new Set(tickers.map((t) => t.trim().toUpperCase()).filter(Boolean))];
  if (distinct.length === 0) return Promise.resolve({});
  return apiGet<Record<string, string>>(`/api/market/logos?tickers=${encodeURIComponent(distinct.join(","))}`);
};

// ---- Recommendations / Agent 5 (Epic 6) ----

/** One agent's diagnostic row on a recommendation card (Story 6.2). */
export interface SignalView {
  agent: string;
  direction: "BULLISH" | "BEARISH" | "NEUTRAL";
  weight: number;
  rationale: string | null;
}

/** A weather-style Probability Forecast Card (Stories 6.1–6.7). */
export interface RecommendationCard {
  id: number;
  ticker: string;
  direction: "BULLISH" | "BEARISH";
  bullProbability: number;
  bearProbability: number;
  confidence: number;
  confidenceCapped: boolean;
  priceTarget: number | null;
  horizon: string | null;
  status: string;
  badge: string | null;
  blackSwanActive: boolean;
  /** When this call was last re-checked (the latest review pass that scored it). */
  createdAt: string;
  /** When this call (same action, unbroken) was first made. */
  callSince: string;
  /** S-C1: the signed-in person's own Take/Decline on this call; `status` stays the shared Investor's. */
  myDecision: "TAKEN" | "DECLINED" | null;
  signals: SignalView[];
  /** The call: STRONG_BUY | BUY | AVOID | STRONG_AVOID (WATCH never reaches the card list). Null on legacy rows. */
  action: "STRONG_BUY" | "BUY" | "WATCH" | "AVOID" | "STRONG_AVOID" | null;
  actionLabel: string | null;
  /** 0–100 conviction: evidence strength + breadth of independent sources + agreement, minus headwinds. */
  convictionScore: number | null;
  /** Recommended holding period in days (7 / 30 / 90) and its human label. */
  holdDays: number | null;
  horizonLabel: string | null;
  /** ISO date (yyyy-mm-dd) to re-check the call by. */
  reviewOn: string | null;
  thesis: string | null;
  reasons: string[];
  caveats: string[];
  exitPlan: string | null;
  sector: string | null;
  /** Lessons learned from past trades that shaped this call (Agent 13), one line each. */
  learned: string[];
  /** What Agent 10 (the chart) contributed. */
  chart: ChartSummary | null;
  /** What Agent 11 (the deep analyst) said, when it has a fresh verdict. */
  deep: DeepSummary | null;
  /** The newest earnings release's guidance direction when the call was made (Agent 14): RAISED | MAINTAINED | LOWERED. */
  guidance: string | null;
  /** Reverse-DCF valuation read when the call was made (Agent 12): CHEAP | FAIR | RICH. */
  valuation: string | null;
  /** What price to buy, sell and stop at — null for WATCH. A CORE_HOLD style has no sellPrice; see sellNote. */
  priceGuidance: PriceGuidance | null;
}

export interface PriceGuidance {
  buyPrice: number;
  buyNote: string;
  /** Null for a CORE_HOLD call — sellNote explains why there's no fixed target. */
  sellPrice: number | null;
  sellNote: string;
  stopPrice: number;
  stopNote: string;
  style: "SWING" | "CORE_HOLD";
}

export interface ChartSummary {
  bias: "BULLISH" | "BEARISH" | "NEUTRAL";
  score: number;
  trend: "UPTREND" | "DOWNTREND" | "SIDEWAYS";
  notes: string[];
  support: number | null;
  resistance: number | null;
  /** The price support/resistance were measured from: live when `levelsLive`, else the last daily close. */
  levelsPrice: number;
  levelsLive: boolean;
  /** ISO date of the newest daily candle behind the study. */
  barsThrough: string;
}

export interface DeepSummary {
  verdict: DeepVerdictName;
  verdictLabel: string;
  holdDays: number | null;
  conviction: number;
  headline: string | null;
  ageDays: number;
  /** The thesis tracker saw new information that undermines this verdict; a re-analysis is queued. */
  atRisk: boolean;
  atRiskReason: string | null;
}

export type DeepVerdictName = "WORTH_BUYING" | "WAIT" | "NOT_WORTH_BUYING";

export const getRecommendations = (): Promise<RecommendationCard[]> =>
  apiGet<RecommendationCard[]>("/api/recommendations");

/** A name Argus is watching but has no clear edge on — with the reason, so silence is explained. */
export interface WatchItem {
  id: number;
  ticker: string;
  convictionScore: number | null;
  reason: string | null;
  sector: string | null;
  createdAt: string;
}

export const getWatching = (): Promise<WatchItem[]> => apiGet<WatchItem[]>("/api/recommendations/watching");

/** What the broad market is doing right now (S&P, VIX, yields, sector ETFs). */
export interface MarketRegimeView {
  state: "RISK_ON" | "RISK_OFF" | "NEUTRAL" | "UNKNOWN";
  summary: string;
  spy1dPct: number | null;
  vix: number | null;
  ratesRising: boolean;
  sectors: { sector: string; changePct: number }[];
}

export const getMarketRegime = (): Promise<MarketRegimeView> => apiGet<MarketRegimeView>("/api/market/regime");

/** {@code entryPrice}/{@code positionSize} are optional (Story 11.1, F22) — meaningful only when
 * `decision` is TAKEN; omit for DECLINED. */
export const decideRecommendation = (
  id: number,
  decision: "TAKEN" | "DECLINED",
  reasoning: string,
  entryPrice?: number | null,
  positionSize?: number | null,
): Promise<void> =>
  apiPost(`/api/recommendations/${id}/decision`, {
    decision,
    reasoning,
    entryPrice: entryPrice ?? null,
    positionSize: positionSize ?? null,
  });

// ---- Ask AI / Conversation (Epic 7, Story 7.1) ----

/** One turn in an Ask-AI conversation. The server is stateless — send the full history each turn. */
export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

/**
 * Ask AI about a recommendation (FR-30). The answer is grounded in that recommendation's signals,
 * diagnostic, and the current portfolio, via the Model Gateway. Returns the assistant's reply.
 */
export const sendRecommendationChat = (
  id: number,
  messages: ChatMessage[],
  deeper = false,
  signal?: AbortSignal,
): Promise<ChatMessage> =>
  apiPost<ChatMessage>(`/api/recommendations/${id}/chat`, { messages, deeper }, signal);

/**
 * Ask AI about the whole portfolio (FR-31). Grounded server-side in holdings + health + upcoming
 * calendar events + recent recommendations + investor profile, via the Model Gateway. When
 * {@code deeper}, the answer is escalated to Claude Haiku (FR-32).
 */
export const sendPortfolioChat = (
  messages: ChatMessage[],
  deeper = false,
  signal?: AbortSignal,
): Promise<ChatMessage> =>
  apiPost<ChatMessage>(`/api/portfolio/chat`, { messages, deeper }, signal);

// ---- Agents / Operations dashboard (Epic 9, Story 9.1) ----

/** Live status of one agent in the fleet (architecture's 8-agent roster). */
export interface AgentStatus {
  id: string;
  code: string;
  name: string;
  description: string;
  /** ACTIVE/IDLE = built & running; PARTIAL = MVP subsystem; PLANNED = roadmap (not built). */
  status: "ACTIVE" | "IDLE" | "PARTIAL" | "PLANNED";
  captured: number;
  captureLabel: string;
  /** ISO instant of the most-recent capture, or null if it has never run. */
  lastActivity: string | null;
  schedule: string;
  /** Optional data-source/spend hint, or null. */
  note: string | null;
  /** Roadmap phase for not-yet-built agents (e.g. "Phase 2"), or null. */
  phase: string | null;
  /** Nominal run cadence in minutes, or null for an agent with no fixed cadence (on demand / continuous). */
  intervalMinutes: number | null;
  /** How long a gap can get before the agent counts as stalled, or null when it never does. */
  staleAfterMinutes: number | null;
}

export const getAgentStatus = (): Promise<AgentStatus[]> =>
  apiGet<AgentStatus[]>("/api/agents/status");

// ---- Agent 9 — on-demand research ----

/** One step in a research plan. `status` is PENDING/RUNNING/DONE while a job is in flight. */
export interface ResearchStep {
  id: string;
  label: string;
  dataSource: "NEWS" | "MACRO" | "SOCIAL" | "INSIDER" | "WEB" | "EARNINGS";
  why: string;
  status: "PENDING" | "RUNNING" | "DONE" | "SKIPPED" | "FAILED";
}

/** A research job's full state — pushed live over `/topic/research/{id}` and returned by every
 * `/api/research` endpoint. `report` is null until DONE; `error` is set only on FAILED. */
export interface ResearchJobView {
  id: number;
  ticker: string;
  status: "PLANNING" | "RESEARCHING" | "REVISING_PLAN" | "SYNTHESIZING" | "DONE" | "FAILED";
  plan: ResearchStep[];
  report: string | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Start a research pass on a ticker; returns immediately, the job runs in the background. */
export const startResearch = (ticker: string): Promise<ResearchJobView> =>
  apiPost<ResearchJobView>("/api/research/jobs", { ticker });

export const getResearchJobs = (): Promise<ResearchJobView[]> =>
  apiGet<ResearchJobView[]>("/api/research/jobs");

export const getResearchJob = (id: number): Promise<ResearchJobView> =>
  apiGet<ResearchJobView>(`/api/research/jobs/${id}`);

/** One proposed (or adopted) keyword from Agent 8's weekly self-learning review. */
export interface MacroKeywordProposal {
  keyword: string;
  why: string;
  corroboration: number;
}

/** Result of one macro-keyword learning review — the "learning feedback" record for Agent 8. */
export interface MacroKeywordReviewResult {
  ran: boolean;
  missesConsidered: number;
  proposals: MacroKeywordProposal[];
  adopted: MacroKeywordProposal[];
  reason: string;
}

/** Manual trigger for Agent 8's keyword-learning review, rather than waiting for the weekly cron. */
export const triggerMacroKeywordReview = (): Promise<MacroKeywordReviewResult> =>
  apiPost<MacroKeywordReviewResult>("/api/intelligence/macro-keywords/review");

/** One item in the dashboard Live Alerts feed — composed from real agent output. */
export interface LiveAlert {
  id: string;
  tier: "critical" | "warning" | "info";
  title: string;
  body: string;
  source: string;
  ticker: string | null;
  time: string | null;
}

export const getLiveAlerts = (): Promise<LiveAlert[]> =>
  apiGet<LiveAlert[]>("/api/alerts/live");

/** Ops summary for the dashboard bottom strip — agents active + cumulative paid (Haiku) spend. */
export interface OpsSummary {
  agentsActive: number;
  agentsTotal: number;
  haikuSpendUsd: number;
}

export const getOpsSummary = (): Promise<OpsSummary> => apiGet<OpsSummary>("/api/ops/summary");

/** Agent 6 — Cost Governor budget posture (Epic 10). */
export interface BudgetStatus {
  spentUsd: number;
  budgetUsd: number;
  percentUsed: number;
  band: "NORMAL" | "NOTICE" | "WARNING" | "CRITICAL";
  month: string;
  daysLeftInMonth: number;
  projectedUsd: number;
  paidCallsBlocked: boolean;
  paidCalls: number;
  localModelCalls: number;
}

export const getBudgetStatus = (): Promise<BudgetStatus> => apiGet<BudgetStatus>("/api/budget/status");

// ---- Agent 2 — Social Media Intelligence ----

/** Crowd sentiment for one ticker over the recent window (StockTwits/Reddit). */
export interface TickerSentiment {
  ticker: string;
  bullish: number;
  bearish: number;
  neutral: number;
  total: number;
  mood: "Bullish" | "Bearish" | "Mixed";
}

export const getSocialSentiment = (): Promise<TickerSentiment[]> =>
  apiGet<TickerSentiment[]>("/api/social/sentiment");

// ---- Agent 4 — Financial Reports (SEC insider activity) ----

/** One insider (Form 4) transaction. */
export interface InsiderActivity {
  ticker: string;
  insiderName: string | null;
  insiderTitle: string | null;
  transactionType: "BUY" | "SELL" | "GRANT" | "OTHER";
  shares: number | null;
  value: number | null;
  filedAt: string | null;
  url: string | null;
}

export const getInsiderActivity = (): Promise<InsiderActivity[]> =>
  apiGet<InsiderActivity[]>("/api/sec/insider");

// ---- Agent 3 — Internet Intelligence (web buzz) ----

/** Web attention for one ticker — Hacker News discussion + Wikipedia pageview trend. */
export interface TickerBuzz {
  ticker: string;
  hnStories: number;
  hnBullish: number;
  hnBearish: number;
  wikiViewsRecent: number;
  attentionRatio: number;
  mood: "Bullish" | "Bearish" | "Trending" | "Quiet";
}

export const getWebBuzz = (): Promise<TickerBuzz[]> => apiGet<TickerBuzz[]>("/api/internet/buzz");

// ---- F11 Personas ----

/** One investor persona's take on a recommendation. */
export interface PersonaTake {
  persona: string;
  key: string;
  lens: string;
  stance: "AGREE" | "DISAGREE" | "CAUTION";
  rationale: string;
}

export const getPersonas = (recommendationId: number): Promise<PersonaTake[]> =>
  apiGet<PersonaTake[]>(`/api/recommendations/${recommendationId}/personas`);

// ---- Bull-vs-bear researcher debate ----

/** One bull-vs-bear researcher debate on a recommendation (TradingAgents-style Researcher Team,
 * adapted to a single combined-JSON call). User-triggered only — escalates to Claude Haiku. */
export interface DebateView {
  id: number;
  bullCase: string;
  bearCase: string;
  synthesis: string;
  verdict: "BULL" | "BEAR" | "SPLIT";
  createdAt: string;
}

export const runDebate = (recommendationId: number): Promise<DebateView> =>
  apiPost<DebateView>(`/api/recommendations/${recommendationId}/debate`);

export const getDebateHistory = (recommendationId: number): Promise<DebateView[]> =>
  apiGet<DebateView[]>(`/api/recommendations/${recommendationId}/debate`);

/** Agent 5's trust posture + track record behind the UNPROVEN/validated badge. */
export interface GraduationSummary {
  state: string;
  badge: string | null;
  canRecommend: boolean;
  trades: number;
  winRatePct: number;
  tradesToValidated: number;
}

export const getGraduation = (): Promise<GraduationSummary> =>
  apiGet<GraduationSummary>("/api/recommendations/graduation");
/** Manual review (Story 6.6) — resume a FROZEN Agent 5 back to SHADOW. A no-op if not frozen. */
export const resumeGraduation = (): Promise<GraduationSummary> =>
  apiPost<GraduationSummary>("/api/recommendations/graduation/resume");

// ---- Web Push (Epic 8, FR-17) ----

/** The VAPID public key the browser passes as `applicationServerKey`. Empty when unconfigured. */
export interface VapidKey {
  publicKey: string;
}

export const getPushKey = (): Promise<VapidKey> => apiGet<VapidKey>("/api/push/key");

/** Persist a browser subscription (its `toJSON()` shape: `{ endpoint, keys: { p256dh, auth } }`). */
export const subscribePush = (subscription: PushSubscriptionJSON): Promise<void> =>
  apiPost("/api/push/subscribe", subscription);

export const unsubscribePush = (endpoint: string): Promise<void> =>
  apiPost("/api/push/unsubscribe", { endpoint });

/** Mirrors `PushController.TestResult`. `delivered` < `devices` means some subscriptions are failing. */
export interface PushTestResult {
  configured: boolean;
  devices: number;
  delivered: number;
}

/** Send a test notification to all subscribed devices — verifies delivery end-to-end. */
export const testPush = (): Promise<PushTestResult> => apiPost<PushTestResult>("/api/push/test");

/** Mirrors `NotificationPreferencesService.View` — global push preferences. */
export interface NotificationPrefs {
  briefingEnabled: boolean;
  breakingEnabled: boolean;
  alertsEnabled: boolean;
  /** 0–23 local; null = no quiet hours. */
  quietStartHour: number | null;
  quietEndHour: number | null;
  mutedTickers: string[];
}

export const getNotificationPrefs = (): Promise<NotificationPrefs> =>
  apiGet<NotificationPrefs>("/api/notifications/preferences");

export const putNotificationPrefs = (prefs: NotificationPrefs): Promise<NotificationPrefs> =>
  apiPut<NotificationPrefs>("/api/notifications/preferences", prefs);

// ---- Investor Profile (Story 7.6) ----

/** Mirrors the backend `InvestorProfileController.InvestorProfileView` — the user-editable profile. */
export interface InvestorProfile {
  /** "CONSERVATIVE" | "BALANCED" | "GROWTH" | "AGGRESSIVE", or null when unset. */
  riskTolerance: string | null;
  /** "LONG_TERM_HOLDER" | "ACTIVE_TRADER" | "MIX", or null when unset. */
  tradingHorizon: string | null;
  financialGoal: string | null;
  /** Destination amount in the home currency, or null. */
  targetAmount: number | null;
  /** ISO date (YYYY-MM-DD) or null. */
  targetDate: string | null;
  /** Overrides the argus.investor.residency config default when set. */
  residency: string | null;
  /** 3-letter code; overrides the argus.investor.home-currency default when set. */
  homeCurrency: string | null;
  notes: string | null;
  /** True until this person has saved (or skipped) a profile at least once — AuthGate uses this to
   * show the first-login questions exactly once. */
  needsOnboarding: boolean;
  updatedAt: string | null;
}

/** The editable fields (the PUT body); `needsOnboarding`/`updatedAt` are server-owned and not sent. */
export type InvestorProfileUpdate = Omit<InvestorProfile, "needsOnboarding" | "updatedAt">;

export const getInvestorProfile = (): Promise<InvestorProfile> =>
  apiGet<InvestorProfile>("/api/investor-profile");

export const putInvestorProfile = (profile: InvestorProfileUpdate): Promise<InvestorProfile> =>
  apiPut<InvestorProfile>("/api/investor-profile", profile);

/** Dismiss the first-login questions without answering — never asked again. */
export const skipOnboarding = (): Promise<InvestorProfile> =>
  apiPost<InvestorProfile>("/api/investor-profile/skip-onboarding");

// ---- Morning Briefing (Epic 8, FR-16) ----

/** Mirrors the backend `BriefingController.BriefingView` record. */
export interface Briefing {
  id: number;
  headline: string;
  body: string;
  generatedAt: string;
  /** True when this briefing was built by the deterministic fallback (model call failed), not the model. */
  fallback: boolean;
}

/** The latest briefing, or `null` when none exists yet — the backend returns 204 (not a JSON body). */
export async function getLatestBriefing(): Promise<Briefing | null> {
  const res = await fetch(`${BASE_URL}/api/briefing/latest`, {
    headers: { Accept: "application/json" },
    credentials: "include",
  });
  if (res.status === 204) return null;
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as Briefing;
}

/** Force a fresh briefing now (manual trigger; the scheduled one runs at 8am Toronto). */
export const generateBriefing = (): Promise<Briefing> =>
  apiPost<Briefing>("/api/briefing/generate");

/** Mirrors the backend `BriefingController.MarketPulseView` record. */
export interface MarketPulse {
  summary: string;
  articleCount: number;
  generatedAt: string;
  /** True only when a refresh actually re-summarized; false means "nothing major since last check". */
  hasUpdates: boolean;
}

/** The latest market pulse, or `null` when none exists yet — the backend returns 204. */
export async function getMarketPulse(): Promise<MarketPulse | null> {
  const res = await fetch(`${BASE_URL}/api/briefing/market-pulse`, {
    headers: { Accept: "application/json" },
    credentials: "include",
  });
  if (res.status === 204) return null;
  if (!res.ok) throw await toApiError(res);
  return (await res.json()) as MarketPulse;
}

/** Re-scan recent market-impacting news and re-summarize; `hasUpdates=false` if nothing new arrived. */
export const refreshMarketPulse = (signal?: AbortSignal): Promise<MarketPulse> =>
  apiPost<MarketPulse>("/api/briefing/market-pulse/refresh", undefined, signal);

// ---- Curated news queue (Dashboard news section) ----

/** Mirrors the backend `NewsController.NewsCardView` record. */
export interface NewsCardItem {
  id: number;
  headline: string;
  summary: string;
  source: string;
  url: string | null;
  tickers: string[];
  /** When the news happened. */
  publishedAt: string;
  /** When Argus fetched the article. */
  fetchedAt: string;
  generatedAt: string | null;
  /** True when the summary is the deterministic fallback (model call failed), not model-written. */
  fallback: boolean;
}

/** Mirrors the backend `NewsController.NewsFeed` record. */
export interface NewsFeed {
  /** The card to read now, or `null` when the queue is empty. */
  card: NewsCardItem | null;
  /** Ready-to-read cards, including the current one (the queue count). */
  remaining: number;
  /** Cards still being summarized in the background. */
  pending: number;
}

/** The current news card plus queue counts. */
export const getNextNews = (): Promise<NewsFeed> => apiGet<NewsFeed>("/api/news/next");

/** Mark the current card read: delete it and get the next one plus updated counts. */
export const markNewsDone = (id: number): Promise<NewsFeed> =>
  apiPost<NewsFeed>(`/api/news/${id}/done`);

/** Mirrors the backend `NewsController.NewsQueue` record. */
export interface NewsQueue {
  /** Every ready-to-read card at once, most important first. */
  cards: NewsCardItem[];
  /** Cards still being summarized in the background. */
  pending: number;
}

/** Every ready news card at once, for the carousel view. */
export const getNewsQueue = (): Promise<NewsQueue> => apiGet<NewsQueue>("/api/news/queue");

// ---- Agent 5 performance / Ops dashboards (Epic 9, Stories 9.2–9.4) ----

/** Win rate over one window. `winRatePct` is null when there are no resolved trades. */
export interface WindowStat {
  trades: number;
  wins: number;
  winRatePct: number | null;
  statisticallyMeaningful: boolean;
}

/** Story 9.2 — Agent 5 accuracy. No avg-gains figure: outcomes carry no P&L (win/loss only). */
export interface AccuracyView {
  all: WindowStat;
  last30d: WindowStat;
  /** The 30 days before {@link last30d} — for S-B1 trend (last 30d vs prior). */
  prior30d: WindowStat;
  last10: WindowStat;
  totalIssued: number;
  taken: number;
  declined: number;
  graduationState: string;
  graduationBadge: string | null;
  /** The paper book split at `currentSystemSince` (yyyy-mm-dd), when conviction scoring and Agents 11-13 went live. */
  oldSystem: EraStat;
  currentSystem: EraStat;
  currentSystemSince: string;
}

/** One era of the paper book. `winRatePct` / `avgReturnPct` are null until a trade has closed. */
export interface EraStat {
  closed: number;
  wins: number;
  winRatePct: number | null;
  avgReturnPct: number | null;
  open: number;
  statisticallyMeaningful: boolean;
}

export const getAccuracy = (): Promise<AccuracyView> =>
  apiGet<AccuracyView>("/api/recommendations/accuracy");

/** Story 9.3 — one agent's share of total signal weight across all recommendations. */
export interface AgentContribution {
  agent: string;
  contributionPct: number;
  signalCount: number;
  underperformer: boolean;
  /** Phase B — realized hit rate over closed paper trades this agent contributed to; null until any. */
  hitRatePct: number | null;
  reliabilitySamples: number;
  /** Phase B — the learned multiplier applied to this agent's weight; null until it has a record. */
  learnedMultiplier: number | null;
}

export interface AttributionView {
  agents: AgentContribution[];
  agentCount: number;
}

export const getAttribution = (): Promise<AttributionView> =>
  apiGet<AttributionView>("/api/recommendations/attribution");

/** Story 9.4 — one probability bin [lowPct, highPct) and the actual hit rate observed in it. */
export interface CalibrationBin {
  lowPct: number;
  highPct: number;
  count: number;
  wins: number;
  actualHitRatePct: number | null;
  sufficient: boolean;
}

/** `brierScore`: 0 = perfect, 0.25 = coin flip, higher = worse; null until outcomes resolve. */
export interface CalibrationView {
  brierScore: number | null;
  bins: CalibrationBin[];
  resolvedCount: number;
  minSampleSize: number;
}

export const getCalibration = (): Promise<CalibrationView> =>
  apiGet<CalibrationView>("/api/recommendations/calibration");

// ---- S-B2: paper-validation trust bar ----

/** One line of the trust-bar checklist: what's required, what Agent 5's current system shows, pass/fail. */
export interface TrustBarCheck {
  key: "state" | "sample" | "winRate" | "brier";
  label: string;
  required: string;
  actual: string;
  pass: boolean;
}

/**
 * Whether the current system's paper record clears the configured trust bar. `headline` is the exact
 * message to show; either way it never implies Argus places orders.
 */
export interface TrustBarView {
  cleared: boolean;
  passed: number;
  total: number;
  checks: TrustBarCheck[];
  headline: string;
  thresholds: { requiredState: string; minClosedTrades: number; minWinRatePct: number; maxBrier: number };
}

export const getTrustBar = (): Promise<TrustBarView> => apiGet<TrustBarView>("/api/recommendations/trust-bar");

// ---- S-B3: per-trade lessons ----

export type LessonChangeKind = "PENDING" | "WEIGHTS_ADJUSTED" | "RULE_ACTIVATED" | "RULE_RETIRED" | "NO_CHANGE";

/** One closed paper trade's lesson: why it entered, how it ended, what it taught, and what changed after. */
export interface TradeLesson {
  id: number;
  tradeId: number;
  recommendationId: number | null;
  ticker: string;
  direction: string;
  won: boolean;
  exitReason: string | null;
  closedAt: string;
  whyEntered: string;
  outcome: string;
  lesson: string;
  /** Friendly names of the agents whose signals drove the call. */
  reliedOn: string[];
  changeKind: LessonChangeKind;
  changeSummary: string | null;
  changeCheckedAt: string | null;
}

export const getLessons = (ticker?: string, limit = 30): Promise<TradeLesson[]> =>
  apiGet<TradeLesson[]>(
    `/api/learning/lessons?limit=${limit}${ticker ? `&ticker=${encodeURIComponent(ticker)}` : ""}`,
  );

// ---- S-B4: pattern library (pre-entry checks) ----

export type PatternAction = "PROCEED" | "SIZE_DOWN" | "TIGHTEN_STOP" | "SKIP" | "NO_PATTERN";

/** One pre-entry consultation: how similar past setups did and what that changed about the new trade. */
export interface PatternCheck {
  id: number;
  recommendationId: number | null;
  ticker: string;
  direction: string;
  checkedAt: string;
  matches: number;
  wins: number;
  winRatePct: number | null;
  avgReturnPct: number | null;
  stopOutPct: number | null;
  action: PatternAction;
  pattern: string | null;
  note: string;
}

export const getPatternChecks = (ticker?: string, limit = 30): Promise<PatternCheck[]> =>
  apiGet<PatternCheck[]>(
    `/api/learning/patterns?limit=${limit}${ticker ? `&ticker=${encodeURIComponent(ticker)}` : ""}`,
  );

// ---- S-B6: playbook × style fit ----

export interface StyleFitCell {
  family: string;
  dimension: string;
  bucket: string;
  trades: number;
  wins: number;
  winRatePct: number | null;
  avgReturnPct: number | null;
  /** False under the sample-size guard: shown, but it never moves a trade. */
  enough: boolean;
}

export interface StyleFitReport {
  /** Dimension key → label, in display order (vol, sector, price, regime, trend). */
  dimensions: Record<string, string>;
  families: { family: string; trades: number; wins: number; winRatePct: number | null }[];
  cells: StyleFitCell[];
  minSample: number;
}

export const getStyleFit = (): Promise<StyleFitReport> => apiGet<StyleFitReport>("/api/learning/style-fit");

// ---- S-B7: strategy sandbox ----

export type SandboxState = "SHADOW" | "CANDIDATE" | "PROMOTED" | "KILLED";

export interface SandboxStrategy {
  acronym: string;
  name: string;
  state: SandboxState;
  /** True only when PROMOTED: the strategy's readings reach live Agent 5 scoring. */
  live: boolean;
  horizonDays: number;
  resolved: number;
  hits: number;
  hitPct: number | null;
  meanExcessPct: number | null;
  openCalls: number;
  /** Resolved shadow calls still needed before a promote/kill decision (0 once decided or eligible). */
  neededToDecide: number;
  reason: string;
  enteredAt: string;
  stateChangedAt: string;
}

export const getStrategySandbox = (): Promise<SandboxStrategy[]> => apiGet<SandboxStrategy[]>("/api/strategies/sandbox");

// ---- S-C3: per-person daily AI / import quotas ----

export interface QuotaUsage {
  kind: "ASK_AI" | "RESEARCH" | "DEEP_ANALYSIS" | "DEBATE" | "IMPORT";
  label: string;
  used: number;
  /** 0 = no cap (quotas off, or the admin, who is exempt by default). */
  cap: number;
}

export const getQuota = (): Promise<Record<string, QuotaUsage>> => apiGet<Record<string, QuotaUsage>>("/api/quota");

/** The lesson for one closed paper trade; rejects (404) until the 5-minute lessons pass has written it. */
export const getLessonForTrade = (tradeId: number): Promise<TradeLesson> =>
  apiGet<TradeLesson>(`/api/learning/lessons/trade/${tradeId}`);

// ---- Regret analysis (the behavioral mirror) ----

/** One decision bucket: decided recs with closed paper legs, and how they went. */
export interface RegretBucket {
  count: number;
  winRatePct: number | null;
  avgReturnPct: number | null;
}

/** Mirrors `PerformanceService.RegretView`; `regretGapPct` > 0 = the declined calls did better. */
export interface RegretView {
  taken: RegretBucket;
  declined: RegretBucket;
  regretGapPct: number | null;
}

export const getRegret = (): Promise<RegretView> =>
  apiGet<RegretView>("/api/recommendations/regret");

// ---- Trade Journal (Story 11.1, F22) ----

/** One journal row — mirrors `JournalService.JournalEntryView`. `outcome` is PENDING until a
 * matching paper leg closes; `outcomeReturnPct` is null while pending. `source` is AGENT for the
 * Investor persona's own paper trades, USER for a card confirmed by hand. */
export interface JournalEntryView {
  decisionId: number;
  recommendationId: number;
  ticker: string;
  direction: string;
  decision: "TAKEN" | "DECLINED";
  source: "USER" | "AGENT";
  decidedAt: string;
  outcome: "WIN" | "LOSS" | "PENDING";
  outcomeReturnPct: number | null;
}

export interface JournalSignalDetail {
  agent: string;
  direction: string;
  weight: number | null;
  rationale: string;
}

export interface JournalPersonaVerdictDetail {
  persona: string;
  key: string;
  stance: string;
  rationale: string;
}

/** The frozen FR-15 rationale snapshot plus entry details and the same outcome as the list view. */
export interface JournalDetailView {
  decisionId: number;
  recommendationId: number;
  ticker: string;
  direction: string;
  bullProbability: number | null;
  bearProbability: number | null;
  confidence: number | null;
  decision: "TAKEN" | "DECLINED";
  source: "USER" | "AGENT";
  reasoning: string | null;
  decidedAt: string;
  entryPrice: number | null;
  positionSize: number | null;
  signals: JournalSignalDetail[];
  personaVerdicts: JournalPersonaVerdictDetail[];
  outcome: "WIN" | "LOSS" | "PENDING";
  outcomeReturnPct: number | null;
}

export const getJournal = (): Promise<JournalEntryView[]> =>
  apiGet<JournalEntryView[]>("/api/journal");

export const getJournalEntry = (decisionId: number): Promise<JournalDetailView> =>
  apiGet<JournalDetailView>(`/api/journal/${decisionId}`);

/** One closed simulated position in the Investor's book (FR-11 follow-up). */
export interface ClosedTradeView {
  ticker: string;
  direction: string;
  returnPct: number | null;
  won: boolean;
  closedAt: string;
  /** The Analyst's post-mortem on a losing call; null for wins. */
  review: string | null;
  /**
   * How the trade ended: HORIZON (ran its course), STOP (the original stop broke), TRAILING_STOP (a stop tightened
   * since entry broke), TAKE_PROFIT (half taken off at the target), THESIS_FLIP (Agent 11 turned against it),
   * THESIS_DECAY (Agent 5's own latest call turned against it).
   */
  exitReason: "HORIZON" | "STOP" | "TRAILING_STOP" | "TAKE_PROFIT" | "THESIS_FLIP" | "THESIS_DECAY" | null;
}

/** Open positions aggregated per ticker, marked to market (unrealizedPct null if unpriced). */
export interface OpenPositionView {
  ticker: string;
  direction: string;
  positions: number;
  notional: number;
  currentPrice: number | null;
  unrealizedPct: number | null;
}

/**
 * The Investor persona's autonomous paper-trading scoreboard: a fixed-notional book opened on Agent 5's
 * calls and marked to market at the horizon — win rate + realized return built with no manual input.
 */
export interface PaperTradeScoreboard {
  openTrades: number;
  closedTrades: number;
  wins: number;
  winRatePct: number | null;
  notionalPerTrade: number;
  deployed: number;
  realizedPnl: number;
  bookReturnPct: number | null;
  /** Live open book: $ committed and its current unrealized return, plus positions per ticker. */
  openDeployed: number;
  openUnrealizedPct: number | null;
  openByTicker: OpenPositionView[];
  recent: ClosedTradeView[];
  management: ManagementView;
}

/**
 * Whether watching open trades beats waiting for the horizon: of the early exits whose original horizon has
 * passed (`measured`), what they returned (`avgRealizedPct`) vs what holding would have (`avgHoldPct`).
 */
export interface ManagementView {
  openTotal: number;
  openWithStop: number;
  openTrailing: number;
  exitsByReason: Record<string, number>;
  earlyExits: number;
  measured: number;
  avgRealizedPct: number | null;
  avgHoldPct: number | null;
}

// ---- "What's new" announcements ----

/** A shared "what's new" note; each person sees it once until they mark it read. */
export interface Announcement {
  id: number;
  title: string;
  points: string[];
  publishedAt: string;
}

/** Notes this person hasn't read yet, newest first. */
export const getUnreadAnnouncements = (): Promise<Announcement[]> => apiGet<Announcement[]>("/api/announcements/unread");

export const markAnnouncementRead = (id: number): Promise<void> => apiPost(`/api/announcements/${id}/read`);

/** One ticker's outcome of an on-demand open-trade review. `call` is null when Agent 5 made none. */
export interface OpenTradeReviewTicker {
  ticker: string;
  call: string | null;
  conviction: number | null;
  openBefore: number;
  exited: string[];
  stopsTightened: number;
  newLegs: number;
}

/** Live status of an open-trade review: the pipeline step, progress, and every finished ticker so far. */
export interface OpenTradeReviewJob {
  id: number;
  step: "LOADING" | "REVIEWING" | "SUMMARIZING" | "DONE" | "FROZEN" | "FAILED";
  total: number;
  done: number;
  currentTicker: string | null;
  currentStage: string | null;
  results: OpenTradeReviewTicker[];
  exited: number | null;
  stopsTightened: number | null;
  startedAt: string;
  finishedAt: string | null;
  error: string | null;
}

/** Admin only: start Agent 5 re-scoring every ticker the paper book holds (or get the review already running). */
export const startOpenTradeReview = (): Promise<OpenTradeReviewJob> =>
  apiPost<OpenTradeReviewJob>("/api/recommendations/open-trades/review");

/** The running or most recent review; undefined (204) if none has run since the server started. */
export const getOpenTradeReview = (): Promise<OpenTradeReviewJob | undefined> =>
  apiGet<OpenTradeReviewJob | undefined>("/api/recommendations/open-trades/review");

// ---- Real-holdings protection (HoldingGuard) ----

export interface GuardedHolding {
  ticker: string;
  account: string;
  price: number | null;
  stop: number;
  startPrice: number;
  brokerStop: number | null;
  brokerConfirmedAt: string | null;
  stopChangedAt: string | null;
  /** The recommended stop differs from what you confirmed setting at your broker. */
  needsBrokerUpdate: boolean;
}

export interface GuardAlert {
  id: number;
  ticker: string;
  account: string;
  kind: "STOP_BROKEN" | "STOP_RAISED" | "CALL_REVERSED" | "THESIS_AT_RISK";
  recommendation: "SELL" | "TIGHTEN";
  detail: string;
  price: number | null;
  stop: number | null;
  status: "OPEN" | "DECIDED";
  createdAt: string;
  /** When Argus decides for you if you haven't. */
  decideAt: string;
  decidedBy: "USER" | "ARGUS" | null;
  decision: "SELL" | "TIGHTEN" | "HOLD" | null;
  decidedAt: string | null;
  /** Price change in the week after the decision (negative after a SELL = it saved money). */
  outcomePct: number | null;
}

export interface GuardView {
  /** Protection is opt-in per person. */
  enabled: boolean;
  holdings: GuardedHolding[];
  open: GuardAlert[];
  history: GuardAlert[];
}

export const getGuard = (): Promise<GuardView> => apiGet<GuardView>("/api/guard");

export const decideGuardAlert = (id: number, decision: "SELL" | "TIGHTEN" | "HOLD"): Promise<void> =>
  apiPost(`/api/guard/alerts/${id}/decide`, { decision });

export const setGuardEnabled = (enabled: boolean): Promise<void> => apiPost("/api/guard/enabled", { enabled });

export const confirmBrokerStop = (ticker: string, account: string): Promise<void> =>
  apiPost("/api/guard/stops/confirm", { ticker, account });

/** One paper trade, buy to sell (open trades carry the live price and unrealized return). */
export interface LedgerRow {
  id: number;
  ticker: string;
  direction: "BULLISH" | "BEARISH";
  /** CURRENT: opened on/after 2026-09-25 by the current system; OLD: the earlier system. */
  system: "CURRENT" | "OLD";
  status: "OPEN" | "CLOSED";
  openedAt: string;
  entryPrice: number;
  shares: number;
  amount: number;
  stopPrice: number | null;
  targetPrice: number | null;
  closedAt: string | null;
  exitPrice: number | null;
  exitReason: string | null;
  heldDays: number | null;
  returnPct: number | null;
  pnl: number | null;
  vsSpyPct: number | null;
  won: boolean | null;
  scaleIn: boolean;
  takeProfitHalf: boolean;
  currentPrice: number | null;
  unrealizedPct: number | null;
  review: string | null;
  /** S-B4: what the pattern library advised at entry; null for trades opened before it existed. */
  patternAdvice: string | null;
  /** S-B6: the evidence family the call led with (NEWS, DEEP, TECHNICAL, ...), and the style-fit note at entry. */
  playbook: string | null;
  styleFit: string | null;
}

export const getTradeLedger = (): Promise<LedgerRow[]> => apiGet<LedgerRow[]>("/api/recommendations/paper-trades/ledger");

/** S-B5: the paper book for one ticker — open position with live P&L, and its past record there. */
export interface PaperTickerView {
  ticker: string;
  /** BULLISH / BEARISH while legs are open; null when flat. */
  openDirection: "BULLISH" | "BEARISH" | null;
  openLegs: number;
  openAmount: number | null;
  unrealizedPct: number | null;
  unrealizedPnl: number | null;
  closedTrades: number;
  wins: number;
  realizedPnl: number | null;
  lastClosedAt: string | null;
  lastResult: "WON" | "LOST" | null;
}

export const getPaperByTicker = (): Promise<PaperTickerView[]> =>
  apiGet<PaperTickerView[]>("/api/recommendations/paper-trades/by-ticker");

export const getPaperTrades = (): Promise<PaperTradeScoreboard> =>
  apiGet<PaperTradeScoreboard>("/api/recommendations/paper-trades");

// ---- Ops: hardware monitor + data freshness (Epic 9, Stories 9.5/9.7) ----

/** Host telemetry. Nullable fields aren't measurable from the JVM on the current host. */
export interface HardwareMetrics {
  ramTotalMb: number;
  ramUsedMb: number;
  ramFreeMb: number;
  jvmHeapUsedMb: number;
  jvmHeapMaxMb: number;
  ssdTotalGb: number;
  ssdUsedGb: number;
  ssdFreeGb: number;
  ssdDaysToFull: number | null;
  cpuLoadPct: number | null;
  processCpuLoadPct: number | null;
  neuralEngineLoadPct: number | null;
  asOf: string;
}

export const getHardware = (): Promise<HardwareMetrics> =>
  apiGet<HardwareMetrics>("/api/ops/hardware");

/** Freshness of one data source. `stale` is true when older than `thresholdMinutes` (or never). */
export interface SourceFreshness {
  source: string;
  label: string;
  lastUpdateAt: string | null;
  ageMinutes: number | null;
  stale: boolean;
  thresholdMinutes: number;
}

export interface FreshnessView {
  sources: SourceFreshness[];
  anyStale: boolean;
}

export const getFreshness = (): Promise<FreshnessView> =>
  apiGet<FreshnessView>("/api/ops/freshness");

// ---- Backup status (Story 10.2) ----

/** One dump kind (full/critical): newest file's time + size, and whether it's overdue. */
export interface BackupKindStatus {
  lastSuccessAt: string | null;
  lastSizeBytes: number | null;
  stale: boolean;
}

/** Mirrors `BackupStatusService.BackupStatusView`. Disabled until ARGUS_BACKUP_DIR is configured. */
export interface BackupStatusView {
  enabled: boolean;
  destinationConnected: boolean;
  full: BackupKindStatus | null;
  critical: BackupKindStatus | null;
}

/** Mirrors `BackupTriggerService.TriggerStatus` — the on-demand "Back Up Now" state. */
export interface BackupTriggerStatus {
  state: "IDLE" | "RUNNING" | "SUCCESS" | "FAILED";
  startedAt: string | null;
  message: string | null;
}

/** Mirrors `OpsController.BackupView`. */
export interface BackupView {
  status: BackupStatusView;
  trigger: BackupTriggerStatus;
}

export const getBackupStatus = (): Promise<BackupView> => apiGet<BackupView>("/api/ops/backup");

/** Kick off an on-demand backup. Returns immediately (RUNNING) — poll getBackupStatus for
 * SUCCESS/FAILED. */
export const triggerBackup = (): Promise<BackupView> => apiPost<BackupView>("/api/ops/backup/trigger");

// ---- Per-agent data storage (Ops) ----

/** Mirrors `StorageService.TableStorage`. */
export interface TableStorage {
  table: string;
  label: string;
  stores: string;
  rows: number;
  bytes: number;
}

/** Mirrors `StorageService.AgentStorage`. */
export interface AgentStorage {
  key: string;
  name: string;
  description: string;
  rows: number;
  bytes: number;
  tables: TableStorage[];
}

/** Mirrors `StorageService.StorageView` — how much data each agent has stored, and where. */
export interface StorageView {
  database: string;
  totalRows: number;
  totalBytes: number;
  generatedAt: string;
  agents: AgentStorage[];
}

export const getStorage = (): Promise<StorageView> => apiGet<StorageView>("/api/ops/storage");

// ---- Smart Cleanup agent (Ops) ----

/** Mirrors `CleanupService.SourceReport` — the plan for one firehose table. */
export interface CleanupSourceReport {
  table: string;
  kind: string;
  rowsTotal: number;
  /** Rows that would be (dry-run) / were (live) deleted. */
  affected: number;
  keptRecent: number;
  keptAnchored: number;
  rollupDays: number;
  freedBytes: number;
  /** Anchored rows newly marked as precedent this run (0 on a dry-run preview). */
  precedentTagged: number;
}

/** Mirrors `CleanupService.CleanupReport`. */
export interface CleanupReport {
  dryRun: boolean;
  startedAt: string;
  finishedAt: string;
  deletedRows: number;
  keptRows: number;
  rolledUpDays: number;
  freedBytes: number;
  sources: CleanupSourceReport[];
  summary: string;
}

/** Mirrors `CleanupController.LastRun`, or null if the agent has never run. */
export interface CleanupLastRun {
  startedAt: string;
  dryRun: boolean;
  deletedRows: number;
  keptRows: number;
  rolledUpDays: number;
  freedBytes: number;
  summary: string;
}

/** Dry-run: compute the keep/delete/roll-up plan, deleting nothing. */
export const previewCleanup = (): Promise<CleanupReport> =>
  apiPost<CleanupReport>("/api/ops/cleanup/preview");

/** Live: roll up then delete the disposable firehose rows. */
export const runCleanup = (): Promise<CleanupReport> => apiPost<CleanupReport>("/api/ops/cleanup/run");

/** The most recent run, or null if never run. */
export const getLastCleanup = (): Promise<CleanupLastRun | null> =>
  apiGet<CleanupLastRun | null>("/api/ops/cleanup/last");

// ---- Analyst Logic Review (LLM proposes, backtest decides) ----

/** Mirrors `LogicReviewController.LastReview`, or null if never run. */
export interface LogicReviewLast {
  ranAt: string;
  model: string | null;
  sampleSize: number;
  beforeBrier: number | null;
  afterBrier: number | null;
  beforeAccuracy: number | null;
  afterAccuracy: number | null;
  adopted: boolean;
  reason: string;
  /** Raw JSON array text: [{agent,factor,why}]. */
  proposals: string;
}

export const getLastLogicReview = (): Promise<LogicReviewLast | null> =>
  apiGet<LogicReviewLast | null>("/api/ops/logic-review/last");

/** Trigger a review now (model proposes, backtest decides). May take ~1-2 min if there's data to review. */
export const runLogicReview = (): Promise<unknown> => apiPost("/api/ops/logic-review/run");

// ---- Watchlist (beyond-portfolio universe) ----

/** Mirrors `WatchlistController.WatchlistView`. */
export interface WatchlistEntry {
  ticker: string;
  source: string; // MANUAL | DISCOVERED
  note: string | null;
  active: boolean;
  addedAt: string;
  expiresAt: string | null;
}

export const getWatchlist = (): Promise<WatchlistEntry[]> => apiGet<WatchlistEntry[]>("/api/watchlist");

export const addWatchlist = (ticker: string, note?: string): Promise<WatchlistEntry> =>
  apiPost<WatchlistEntry>("/api/watchlist", { ticker, note });

export async function removeWatchlist(ticker: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/watchlist/${encodeURIComponent(ticker)}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) throw await toApiError(res);
}

/** Run auto-discovery now: promote trending non-portfolio tickers. Returns the updated list. */
export const discoverWatchlist = (): Promise<WatchlistEntry[]> =>
  apiPost<WatchlistEntry[]>("/api/watchlist/discover");

// ---- Degraded Mode coordinator (Epic 10, Story 10.4) ----

/** Current platform mode. `since` is an ISO instant; `reason` is a short human explanation. */
export interface PlatformModeView {
  mode: "NORMAL" | "DEGRADED";
  since: string;
  reason: string;
}

export const getPlatformMode = (): Promise<PlatformModeView> =>
  apiGet<PlatformModeView>("/api/ops/platform-mode");


// ---- Agent 11: deep analysis ----

export interface DeepRunStatus {
  id: number;
  ticker: string;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED";
  stage: string | null;
}

/** One finished deep analysis with all its reasoning (Agent 11). */
export interface DeepAnalysisView {
  id: number;
  ticker: string;
  verdict: DeepVerdictName | null;
  verdictLabel: string | null;
  holdDays: number | null;
  conviction: number | null;
  headline: string | null;
  thesis: string | null;
  bullCase: string | null;
  bearCase: string | null;
  risks: string[];
  catalysts: string[];
  invalidation: string | null;
  technicalSummary: string | null;
  fundamentalSummary: string | null;
  catalystSummary: string | null;
  macroSummary: string | null;
  skepticView: string | null;
  guardNotes: string[];
  technicalScore: number | null;
  fundamentalScore: number | null;
  consensusScore: number | null;
  model: string | null;
  finishedAt: string | null;
  expiresAt: string | null;
  stale: boolean;
  inProgress: DeepRunStatus | null;
  /** Price whose breach proves the verdict wrong, and the price when it was made. */
  invalidationPrice: number | null;
  priceAtAnalysis: number | null;
  thesisStatus: "INTACT" | "AT_RISK";
  thesisReason: string | null;
  /** A beginner-friendly rendering of the thesis/bull/bear case, generated the first time someone asks for it. */
  plainExplanation: string | null;
}

export interface DeepHistoryItem {
  id: number;
  status: string;
  verdict: DeepVerdictName | null;
  conviction: number | null;
  holdDays: number | null;
  at: string;
}

/** One ticker's newest verdict, any run in progress, and its analysis history (GET /api/deep-analysis/{ticker}). */
export interface DeepTickerView {
  latest: DeepAnalysisView | null;
  inProgress: DeepRunStatus | null;
  history: DeepHistoryItem[];
}

export interface DeepScorecardCell {
  verdict: DeepVerdictName;
  label: string;
  horizonDays: number;
  n: number;
  meanExcessPct: number;
  hitRate: number | null;
}

export interface DeepScorecardRow {
  ticker: string;
  verdict: DeepVerdictName;
  analyzedOn: string;
  entryPrice: number | null;
  sincePct: number | null;
  sinceExcessPct: number | null;
  matured: Record<string, number>;
}

/** Agent 11 measured against the S&P 500 (7/30/90 days) and since each call. */
export interface DeepScorecard {
  totalVerdicts: number;
  minForTrackRecord: number;
  cells: DeepScorecardCell[];
  rows: DeepScorecardRow[];
}

export interface DeepQueue {
  running: string | null;
  queued: number;
  tickers: string[];
}

export const getDeepAnalyses = (): Promise<DeepAnalysisView[]> => apiGet<DeepAnalysisView[]>("/api/deep-analysis");
export const getDeepQueue = (): Promise<DeepQueue> => apiGet<DeepQueue>("/api/deep-analysis/queue");
export const runDeepAnalysis = (ticker: string): Promise<DeepRunStatus> =>
  apiPost<DeepRunStatus>(`/api/deep-analysis/${encodeURIComponent(ticker)}/run`);
/** 404 (ApiError) when Agent 11 has never analysed the ticker. */
export const getDeepAnalysisFor = (ticker: string): Promise<DeepTickerView> =>
  apiGet<DeepTickerView>(`/api/deep-analysis/${encodeURIComponent(ticker)}`);
export const getDeepScorecard = (): Promise<DeepScorecard> => apiGet<DeepScorecard>("/api/deep-analysis/scorecard");
/** "Explain like I'm new to investing" — generated on first call, cached on the analysis after. */
export const explainDeepAnalysis = (ticker: string): Promise<{ text: string }> =>
  apiPost<{ text: string }>(`/api/deep-analysis/${encodeURIComponent(ticker)}/explain`);

/** One saved point in Agent 11's scorecard history — persisted daily so the track record survives a restart. */
export interface DeepScorecardSnapshotView {
  verdict: DeepVerdictName;
  label: string;
  horizonDays: number;
  observations: number;
  meanExcessPct: number;
  hitRate: number | null;
  totalVerdicts: number;
  computedAt: string;
}
export const getDeepScorecardHistory = (): Promise<DeepScorecardSnapshotView[]> =>
  apiGet<DeepScorecardSnapshotView[]>("/api/deep-analysis/scorecard/history");
export const saveDeepScorecardSnapshot = (): Promise<{ cellsWritten: number; historySize: number }> =>
  apiPost<{ cellsWritten: number; historySize: number }>("/api/deep-analysis/scorecard/snapshot");

/** "HAIKU" (the paid Claude Haiku escalation) or "LOCAL" (the free Gemma fallback) — whichever actually answered. */
export type VerdictModel = "HAIKU" | "LOCAL";

export interface DeepModelCell {
  model: VerdictModel;
  verdict: DeepVerdictName;
  label: string;
  horizonDays: number;
  n: number;
  meanExcessPct: number;
  hitRate: number | null;
}

/** Is paying for Haiku's verdict call actually better than Gemma alone? The measured, not reasoned-about, answer. */
export interface DeepModelComparison {
  totalVerdicts: Partial<Record<VerdictModel, number>>;
  cells: DeepModelCell[];
}
export const getDeepScorecardByModel = (): Promise<DeepModelComparison> =>
  apiGet<DeepModelComparison>("/api/deep-analysis/scorecard/by-model");

export interface DeepModelSnapshotView {
  model: VerdictModel;
  verdict: DeepVerdictName;
  label: string;
  horizonDays: number;
  observations: number;
  meanExcessPct: number;
  hitRate: number | null;
  computedAt: string;
}
export const getDeepScorecardByModelHistory = (): Promise<DeepModelSnapshotView[]> =>
  apiGet<DeepModelSnapshotView[]>("/api/deep-analysis/scorecard/by-model/history");

// ---- Agent 12: fundamentals · Agent 14: filings ----

export interface FundamentalsRow {
  ticker: string;
  name: string | null;
  industry: string | null;
  score: number;
  bias: "BULLISH" | "BEARISH" | "NEUTRAL";
  valuationVerdict: "CHEAP" | "FAIR" | "RICH" | null;
  impliedGrowthPct: number | null;
  deliveredGrowthPct: number | null;
  peerPePremiumPct: number | null;
  notes: string[];
}

export const getFundamentals = (): Promise<FundamentalsRow[]> => apiGet<FundamentalsRow[]>("/api/fundamentals");

export interface FundamentalsQuarter {
  endDate: string;
  revenue: number | null;
  grossProfit: number | null;
  operatingIncome: number | null;
  netIncome: number | null;
}
export interface EarningsSurprise {
  period: string;
  actual: number | null;
  estimate: number | null;
  surprisePct: number | null;
}
export interface AnalystConsensus {
  period: string;
  strongBuy: number;
  buy: number;
  hold: number;
  sell: number;
  strongSell: number;
  bullishShare: number | null;
  previousBullishShare: number | null;
}
export interface PeerRow {
  symbol: string;
  pe: number | null;
  ps: number | null;
  evEbitda: number | null;
  revenueGrowth: number | null;
  netMargin: number | null;
}
export interface PeerComparison {
  peers: string[];
  medianPe: number | null;
  pe: number | null;
  premiumPct: number | null;
  rows: PeerRow[];
  medianPs: number | null;
  psPremiumPct: number | null;
  medianEvEbitda: number | null;
  evEbitdaPremiumPct: number | null;
}
export interface ValuationView {
  impliedGrowthPct: number;
  deliveredGrowthPct: number | null;
  discountRatePct: number;
  gapPts: number | null;
  verdict: "CHEAP" | "FAIR" | "RICH";
  price: number;
  epsTtm: number;
  summary: string;
}
/** Full per-ticker fundamentals (Agent 12) — the detail a ticker's Fundamentals tab shows. */
export interface FundamentalsDetail {
  ticker: string;
  applicable: boolean;
  name: string | null;
  industry: string | null;
  marketCapMillions: number | null;
  ratios: Record<string, number>;
  quarters: FundamentalsQuarter[];
  earnings: EarningsSurprise[];
  analysts: AnalystConsensus | null;
  peers: PeerComparison | null;
  score: number;
  bias: "BULLISH" | "BEARISH" | "NEUTRAL";
  notes: string[];
  fetchedAt: string;
  valuation: ValuationView | null;
}
export const getFundamentalsFor = (ticker: string): Promise<FundamentalsDetail> =>
  apiGet<FundamentalsDetail>(`/api/fundamentals/${encodeURIComponent(ticker)}`);

export interface FilingRow {
  ticker: string;
  form: string;
  kind: "EARNINGS_RELEASE" | "QUARTERLY_REPORT" | "ANNUAL_REPORT";
  filedAt: string;
  summary: string;
  guidance: string | null;
  tone: string | null;
  score: number;
}

export const getFilings = (): Promise<FilingRow[]> => apiGet<FilingRow[]>("/api/filings");

export interface FilingDigestItem {
  form: string;
  kind: "EARNINGS_RELEASE" | "QUARTERLY_REPORT" | "ANNUAL_REPORT";
  filedAt: string;
  summary: string;
  guidance: string | null;
  guidanceDetail: string | null;
  tone: string | null;
  score: number;
  verifiedFacts: number;
  droppedFacts: number;
  accession: string;
}
/** Full per-ticker filings read (Agent 14) — the detail a ticker's Fundamentals/Filings tab shows. */
export interface FilingsDetail {
  ticker: string;
  score: number;
  guidance: string | null;
  tone: string | null;
  latestEarningsDate: string | null;
  headline: string | null;
  ageDays: number;
  digests: FilingDigestItem[];
}
export const getFilingsFor = (ticker: string): Promise<FilingsDetail> =>
  apiGet<FilingsDetail>(`/api/filings/${encodeURIComponent(ticker)}`);

// ---- Agent 15: academic strategies ----

/**
 * One published strategy in the library. `publishedTStat` is what the paper reported — context only, never used
 * to decide anything. `holdoutTStat` is what Argus measured on its own data, out of sample, and is what counts.
 */
export interface StrategyRow {
  acronym: string;
  name: string;
  citation: string;
  definition: string | null;
  dataCategory: string | null;
  economicCategory: string | null;
  /** Chen & Zimmermann's own replication verdict: 1_clear | 2_likely | indirect | 4_not. */
  replicationGrade: string | null;
  publishedTStat: number | null;
  sign: number | null;
  status: "ACTIVE" | "CANDIDATE" | "REJECTED" | "UNIMPLEMENTED";
  horizonDays: number | null;
  measuredExcessPct: number | null;
  tStat: number | null;
  holdoutMeanExcess: number | null;
  holdoutTStat: number | null;
  observations: number | null;
  verdict: "PASS" | "FAIL_INSAMPLE" | "FAIL_HOLDOUT" | "INSUFFICIENT_DATA" | null;
  note: string | null;
}

export interface StrategyLibrary {
  total: number;
  predictors: number;
  /** Published signals the researchers showed do NOT predict returns — the literature's own control group. */
  placebos: number;
  computable: number;
  active: number;
  rejected: number;
  universeSize: number;
  universeCovered: number;
  strategies: StrategyRow[];
}

export interface StrategyReading {
  acronym: string;
  name: string;
  citation: string;
  percentile: number;
  sign: number;
  view: number;
  direction: string;
  measuredTStat: number;
  horizonDays: number;
  asOf: string;
}

export const getStrategyLibrary = (): Promise<StrategyLibrary> => apiGet<StrategyLibrary>("/api/strategies");
export const getStrategyReadings = (ticker: string): Promise<StrategyReading[]> =>
  apiGet<StrategyReading[]>(`/api/strategies/${encodeURIComponent(ticker)}`);
export const revalidateStrategies = (): Promise<{ tests: number; passed: number; active: number }> =>
  apiPost<{ tests: number; passed: number; active: number }>("/api/strategies/validate");

// ---- Agent 10: chart study ----

export interface ChartStudyRow {
  ticker: string;
  asOf: string;
  lastClose: number;
  bias: "BULLISH" | "BEARISH" | "NEUTRAL";
  score: number;
  trend: "UPTREND" | "DOWNTREND" | "SIDEWAYS";
  ret5d: number | null;
  ret20d: number | null;
  rsi14: number | null;
  relStrength60d: number | null;
  patterns: string[];
  support: number | null;
  resistance: number | null;
  headlineNotes: string[];
}

export interface ChartBar {
  time: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface ChartPoint {
  time: string;
  value: number;
}

export interface ChartDetail {
  study: ChartStudyRow;
  notes: string[];
  support: number | null;
  resistance: number | null;
  candles: ChartBar[];
  sma20: ChartPoint[];
  sma50: ChartPoint[];
  sma200: ChartPoint[];
  /** What support/resistance were measured from: live when `levelsLive`, else the last daily close. */
  levelsPrice: number;
  levelsLive: boolean;
}

export const getChartStudies = (): Promise<ChartStudyRow[]> => apiGet<ChartStudyRow[]>("/api/technical/studies");
export const getChartDetail = (ticker: string): Promise<ChartDetail> =>
  apiGet<ChartDetail>(`/api/technical/${encodeURIComponent(ticker)}`);

// ---- Agent 13: the trade learner ----

export interface LearnedRuleView {
  id: number;
  kind: "PENALTY" | "BOOST" | "BLOCK" | "CAP_HOLD" | "SIZE";
  status: "PROPOSED" | "ACTIVE" | "RETIRED" | "REJECTED";
  description: string;
  explanation: string | null;
  effect: number;
  bets: number;
  winRate: number | null;
  meanExcess: number | null;
  holdoutBets: number | null;
  holdoutMeanExcess: number | null;
  note: string | null;
  activatedAt: string | null;
}

export interface LearningReportView {
  tradesAnalyzed: number;
  independentBets: number;
  baselineWin: number | null;
  baselineExcess: number | null;
  losses: string | null;
  wins: string | null;
  narrative: string | null;
  model: string | null;
  at: string;
}

export interface LearningView {
  report: LearningReportView | null;
  activeRules: LearnedRuleView[];
  otherRules: LearnedRuleView[];
  running: boolean;
}

export const getLearning = (): Promise<LearningView> => apiGet<LearningView>("/api/learning");
export const runLearning = (): Promise<{ started: boolean }> => apiPost<{ started: boolean }>("/api/learning/run");
