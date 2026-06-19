// Typed REST client for the Argus backend. Success returns the resource directly;
// errors are RFC 9457 problem+json, parsed into a typed ApiError.

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Mirrors the backend `SystemInfo` record. */
export interface SystemInfo {
  name: string;
  version: string;
  profile: string;
  time: string;
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

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { Accept: "application/json" },
  });
  if (!res.ok) {
    let problem: ProblemDetail = { status: res.status, title: res.statusText };
    try {
      problem = (await res.json()) as ProblemDetail;
    } catch {
      // non-JSON error body — keep the status/statusText fallback
    }
    throw new ApiError(problem, res.status);
  }
  return (await res.json()) as T;
}

export const getSystemInfo = (): Promise<SystemInfo> =>
  apiGet<SystemInfo>("/api/system-info");
