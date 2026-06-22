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

/** Mirrors the backend `AuthStatus` record (Story 2.1 + 2.2). */
export interface AuthStatus {
  pinSet: boolean;
  authenticated: boolean;
  passkeyEnrolled: boolean;
}

/** Mirrors the backend `WebAuthnController.PasskeyInfo` record (Story 2.2). */
export interface PasskeyInfo {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
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

// ---- Biometric / WebAuthn (Story 2.2) ----

/** True if the browser exposes the WebAuthn platform API (e.g. iOS Safari over HTTPS). */
export function webauthnSupported(): boolean {
  return typeof window !== "undefined" && typeof window.PublicKeyCredential !== "undefined";
}

// The JSON-based WebAuthn helpers (iOS 17.4+/modern browsers) aren't in every TS DOM lib yet.
type PkcJsonStatic = {
  parseCreationOptionsFromJSON(json: unknown): PublicKeyCredentialCreationOptions;
  parseRequestOptionsFromJSON(json: unknown): PublicKeyCredentialRequestOptions;
};
type CredentialWithToJSON = { toJSON(): unknown };

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
  const { publicKey } = (await startRes.json()) as { publicKey: unknown };

  const options = (PublicKeyCredential as unknown as PkcJsonStatic).parseCreationOptionsFromJSON(publicKey);
  const credential = await navigator.credentials.create({ publicKey: options });
  if (!credential) throw new Error("Enrollment cancelled");

  const finishRes = await fetch(
    `${BASE_URL}/api/auth/webauthn/register/finish?label=${encodeURIComponent(label)}`,
    {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
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
