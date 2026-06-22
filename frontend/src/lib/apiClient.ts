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

/** Mirrors the backend `AuthStatus` record (Story 2.1). */
export interface AuthStatus {
  pinSet: boolean;
  authenticated: boolean;
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

async function toApiError(res: Response): Promise<ApiError> {
  let problem: ProblemDetail = { status: res.status, title: res.statusText };
  try {
    problem = (await res.json()) as ProblemDetail;
  } catch {
    // non-JSON error body — keep the status/statusText fallback
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
export async function apiPost<T = void>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    credentials: "include",
    body: body === undefined ? undefined : JSON.stringify(body),
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
