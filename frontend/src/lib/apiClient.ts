// Typed REST client for the Argus backend. Success returns the resource directly;
// errors are RFC 9457 problem+json, parsed into a typed ApiError.
//
// `credentials: "include"` sends the HttpOnly ARGUS_SESSION cookie (Story 2.1) on every
// call. On the Mini this is single-origin; in local dev it's cross-port, which the backend
// CORS config allows (allowCredentials + explicit origin).

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Mirrors the backend `SystemInfo` record. */
export interface SystemInfo {
  name: string;
  version: string;
  profile: string;
  time: string;
}

/** Mirrors the backend `AuthStatus` record (Story 2.1 + 2.2 + 2.6). */
export interface AuthStatus {
  pinSet: boolean;
  authenticated: boolean;
  passkeyEnrolled: boolean;
  fullyLocked: boolean;
  lockoutSecondsRemaining: number;
}

/** Mirrors the backend `WebAuthnController.PasskeyInfo` record (Story 2.2). */
export interface PasskeyInfo {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

/** RFC 9457 Problem Details body (+ lockout extensions from Story 2.6). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** Lockout (FR-38): seconds until a timed lockout lifts. */
  retryAfterSeconds?: number;
  /** Lockout (FR-38): true when fully locked (needs another device). */
  fullyLocked?: boolean;
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
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { Accept: "application/json" },
    credentials: "include",
  });
  if (!res.ok) {
    throw await toApiError(res);
  }
  return (await res.json()) as T;
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

export const setupPin = (pin: string): Promise<void> =>
  apiPost("/api/auth/pin", { pin });

export const login = (pin: string): Promise<AuthStatus> =>
  apiPost<AuthStatus>("/api/auth/login", { pin });

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

// ---- Biometric / WebAuthn (Story 2.2) ----

// The JSON-based WebAuthn helpers (iOS 17.4+/modern browsers) aren't in every TS DOM lib yet.
type PkcJsonStatic = {
  parseCreationOptionsFromJSON(json: unknown): PublicKeyCredentialCreationOptions;
  parseRequestOptionsFromJSON(json: unknown): PublicKeyCredentialRequestOptions;
};
type CredentialWithToJSON = { toJSON(): unknown };

/**
 * True only if the browser supports the JSON WebAuthn API this client actually uses
 * (`parse*OptionsFromJSON` + `PublicKeyCredential.prototype.toJSON`) — not just the presence of
 * `PublicKeyCredential`. Older Safari/iOS 16 have the latter but not the former, which would make
 * the ceremony throw; gating on the real methods keeps the biometric button honest.
 */
export function webauthnSupported(): boolean {
  if (typeof window === "undefined" || typeof window.PublicKeyCredential === "undefined") {
    return false;
  }
  const pkc = PublicKeyCredential as unknown as Partial<PkcJsonStatic>;
  const proto = PublicKeyCredential.prototype as unknown as { toJSON?: unknown };
  return (
    typeof pkc.parseRequestOptionsFromJSON === "function" &&
    typeof pkc.parseCreationOptionsFromJSON === "function" &&
    typeof proto.toJSON === "function"
  );
}

const CEREMONY_HEADER = "X-Argus-Ceremony";

/** Run the assertion ceremony (Face/Touch ID) and start a session. Throws on failure/cancel. */
export async function biometricLogin(): Promise<void> {
  const startRes = await fetch(`${BASE_URL}/api/auth/webauthn/login/start`, {
    method: "POST",
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!startRes.ok) throw await toApiError(startRes);
  const ceremony = startRes.headers.get(CEREMONY_HEADER) ?? "";
  const { publicKey } = (await startRes.json()) as { publicKey: unknown };

  const options = (PublicKeyCredential as unknown as PkcJsonStatic).parseRequestOptionsFromJSON(publicKey);
  const credential = await navigator.credentials.get({ publicKey: options });
  if (!credential) throw new Error("Biometric cancelled");

  const finishRes = await fetch(`${BASE_URL}/api/auth/webauthn/login/finish`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", [CEREMONY_HEADER]: ceremony },
    body: JSON.stringify((credential as unknown as CredentialWithToJSON).toJSON()),
  });
  if (!finishRes.ok) throw await toApiError(finishRes);
}

/** Run the registration ceremony to enroll a new passkey (requires an authenticated session). */
export async function enrollPasskey(label: string): Promise<void> {
  const startRes = await fetch(`${BASE_URL}/api/auth/webauthn/register/start`, {
    method: "POST",
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (!startRes.ok) throw await toApiError(startRes);
  const ceremony = startRes.headers.get(CEREMONY_HEADER) ?? "";
  const { publicKey } = (await startRes.json()) as { publicKey: unknown };

  const options = (PublicKeyCredential as unknown as PkcJsonStatic).parseCreationOptionsFromJSON(publicKey);
  const credential = await navigator.credentials.create({ publicKey: options });
  if (!credential) throw new Error("Enrollment cancelled");

  const finishRes = await fetch(
    `${BASE_URL}/api/auth/webauthn/register/finish?label=${encodeURIComponent(label)}`,
    {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json", [CEREMONY_HEADER]: ceremony },
      body: JSON.stringify((credential as unknown as CredentialWithToJSON).toJSON()),
    },
  );
  if (!finishRes.ok) throw await toApiError(finishRes);
}

export const listPasskeys = (): Promise<PasskeyInfo[]> =>
  apiGet<PasskeyInfo[]>("/api/auth/webauthn/credentials");

export async function revokePasskey(id: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/auth/webauthn/credentials/${encodeURIComponent(id)}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (!res.ok) throw await toApiError(res);
}

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

/** Upload a brokerage statement PDF; returns the parsed preview (nothing is persisted yet). */
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

/** Commit a staged import's holdings into the portfolio. */
export const confirmImport = (importId: number): Promise<Position[]> =>
  apiPost<Position[]>(`/api/portfolio/imports/${importId}/confirm`);

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
}

/** Mirrors the backend `PortfolioSnapshot` record — totals in CAD. Pushed live on `/topic/portfolio`. */
export interface PortfolioSnapshot {
  totalValueCad: number | null;
  totalCostCad: number | null;
  totalPnlCad: number | null;
  anyAfterHours: boolean;
  asOf: string;
  positions: PositionValue[];
}

export const getPortfolioValue = (): Promise<PortfolioSnapshot> =>
  apiGet<PortfolioSnapshot>("/api/portfolio/value");

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

// ---- Economic calendar / Agent 7 (Epic 5) ----

/** An upcoming calendar event; `quietPeriod` (CLEAR|NOTE|QUIET) is set only for earnings (Story 5.3). */
export interface UpcomingEvent {
  id: number;
  type: string;
  ticker: string | null;
  title: string;
  eventDate: string;
  daysUntil: number;
  quietPeriod: string | null;
}

export const getUpcomingEvents = (): Promise<UpcomingEvent[]> =>
  apiGet<UpcomingEvent[]>("/api/calendar/upcoming");

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
  createdAt: string;
  signals: SignalView[];
}

export const getRecommendations = (): Promise<RecommendationCard[]> =>
  apiGet<RecommendationCard[]>("/api/recommendations");

export const decideRecommendation = (
  id: number,
  decision: "TAKEN" | "DECLINED",
  reasoning: string,
): Promise<void> =>
  apiPost(`/api/recommendations/${id}/decision`, { decision, reasoning });

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

/** Live status of one agent in the fleet (architecture's 7-agent roster). */
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
}

export const getAgentStatus = (): Promise<AgentStatus[]> =>
  apiGet<AgentStatus[]>("/api/agents/status");

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
