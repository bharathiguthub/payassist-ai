import { useQuery } from '@tanstack/react-query';
import { ApiError, apiFetch, CSRF_COOKIE, readCookie } from '../api/client';
import type { CurrentUser, Role } from '../api/types';

export const LOGIN_PATH = '/oauth2/authorization/keycloak';
export const LOGOUT_PATH = '/logout';

/** null = not logged in (401). Any other error is surfaced to the UI. */
export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  try {
    return await apiFetch<CurrentUser>('/api/v1/me');
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
}

export function useCurrentUser() {
  return useQuery({ queryKey: ['me'], queryFn: fetchCurrentUser, staleTime: 60_000, retry: false });
}

export function hasAnyRole(user: CurrentUser | null | undefined, roles: readonly Role[]): boolean {
  return !!user && user.roles.some((r) => roles.includes(r));
}

/** Full-page navigation: the BFF starts the OIDC Authorization Code + PKCE flow with Keycloak. */
export function login(): void {
  window.location.assign(LOGIN_PATH);
}

/**
 * Logout must be a top-level POST (not fetch) so the browser follows the redirect chain to
 * Keycloak's end-session endpoint. The CSRF token travels as the _csrf form field.
 */
export function logout(): void {
  const form = document.createElement('form');
  form.method = 'POST';
  form.action = LOGOUT_PATH;
  const csrf = document.createElement('input');
  csrf.type = 'hidden';
  csrf.name = '_csrf';
  csrf.value = readCookie(CSRF_COOKIE) ?? '';
  form.appendChild(csrf);
  document.body.appendChild(form);
  form.submit();
}
