import type { ProblemDetail } from './types';

/**
 * HTTP client for the BFF. The browser never sees OAuth tokens: requests are authenticated by the
 * HttpOnly session cookie, which the browser attaches automatically because the SPA and API share
 * one origin. State-changing requests echo the XSRF-TOKEN cookie in the X-XSRF-TOKEN header.
 */

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

export const CORRELATION_HEADER = 'X-Correlation-Id';

/** An HTTP failure (status 0 = the request never reached the server). */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | undefined;
  /** Server request ID, to quote to support; never sensitive. */
  readonly correlationId: string | undefined;

  constructor(status: number, problem?: ProblemDetail, correlationId?: string) {
    super(
      problem?.detail ??
        problem?.title ??
        (status === 0 ? 'The server could not be reached' : `Request failed with status ${status}`),
    );
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
    this.correlationId = problem?.correlationId ?? correlationId;
  }
}

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const match = document.cookie
    .split(';')
    .map((c) => c.trim())
    .find((c) => c.startsWith(prefix));
  return match ? decodeURIComponent(match.slice(prefix.length)) : undefined;
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie(CSRF_COOKIE);
    if (token) headers.set(CSRF_HEADER, token);
  }

  let response: Response;
  try {
    response = await fetch(path, { ...init, method, headers, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0); // offline, DNS, CORS or connection reset: no response at all
  }

  if (!response.ok) {
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = undefined;
    }
    throw new ApiError(response.status, problem, response.headers?.get(CORRELATION_HEADER) ?? undefined);
  }
  return (await response.json()) as T;
}

/** Builds a query string, dropping empty values. */
export function toQuery(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}
