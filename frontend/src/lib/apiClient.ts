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

/** Uninvested cash per account+currency, folded into the portfolio total. */
export interface CashBalanceView {
  id: number;
  account: string;
  currency: string;
  amount: number;
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

// ---- Morning Briefing (Epic 8, FR-16) ----

/** Mirrors the backend `BriefingController.BriefingView` record. */
export interface Briefing {
  id: number;
  headline: string;
  body: string;
  generatedAt: string;
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
  last10: WindowStat;
  totalIssued: number;
  taken: number;
  declined: number;
  graduationState: string;
  graduationBadge: string | null;
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

export interface CalibrationView {
  bins: CalibrationBin[];
  resolvedCount: number;
  minSampleSize: number;
}

export const getCalibration = (): Promise<CalibrationView> =>
  apiGet<CalibrationView>("/api/recommendations/calibration");

/** One closed simulated position in the Investor's book (FR-11 follow-up). */
export interface ClosedTradeView {
  ticker: string;
  direction: string;
  returnPct: number | null;
  won: boolean;
  closedAt: string;
  /** The Analyst's post-mortem on a losing call; null for wins. */
  review: string | null;
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
}

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
