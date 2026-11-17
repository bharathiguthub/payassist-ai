import type { ProblemDetail } from './types';

/**
 * HTTP client for the BFF. The browser never sees OAuth tokens: requests are authenticated by the
 * HttpOnly session cookie, which the browser attaches automatically because the SPA and API share
 * one origin. State-changing requests echo the XSRF-TOKEN cookie in the X-XSRF-TOKEN header.
 */

export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-XSRF-TOKEN';

export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | undefined;

  constructor(status: number, problem?: ProblemDetail) {
    super(problem?.detail ?? problem?.title ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
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

  const response = await fetch(path, { ...init, method, headers, credentials: 'same-origin' });

  if (!response.ok) {
    let problem: ProblemDetail | undefined;
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      problem = undefined;
    }
    throw new ApiError(response.status, problem);
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
