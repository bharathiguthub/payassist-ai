import { http, HttpResponse } from 'msw';
import { ApiError, apiFetch, readCookie, toQuery } from './client';
import { logout } from '../auth/auth';
import { server } from '../test/server';

describe('api client', () => {
  it('reads cookies by exact name', () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123; path=/';
    document.cookie = 'OTHER-XSRF-TOKEN=nope; path=/';

    expect(readCookie('XSRF-TOKEN')).toBe('abc=123');
    expect(readCookie('MISSING')).toBeUndefined();
  });

  it('sends the CSRF header on state-changing requests only', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; path=/';
    const seen: Record<string, string | null> = {};
    server.use(
      http.get('/api/test', ({ request }) => {
        seen.get = request.headers.get('X-XSRF-TOKEN');
        return HttpResponse.json({});
      }),
      http.post('/api/test', ({ request }) => {
        seen.post = request.headers.get('X-XSRF-TOKEN');
        return HttpResponse.json({});
      }),
    );

    await apiFetch('/api/test');
    await apiFetch('/api/test', { method: 'POST' });

    expect(seen.get).toBeNull();
    expect(seen.post).toBe('token-123');
  });

  it('raises ApiError carrying Problem Details', async () => {
    server.use(
      http.get('/api/test', () =>
        HttpResponse.json({ title: 'Resource not found', detail: "Payment 'X' was not found", status: 404 }, { status: 404 }),
      ),
    );

    const error = await apiFetch('/api/test').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).message).toBe("Payment 'X' was not found");
  });

  it('builds query strings without empty values', () => {
    expect(toQuery({ q: 'PAY', status: '', page: 0 })).toBe('?q=PAY&page=0');
    expect(toQuery({ q: undefined })).toBe('');
  });
});

describe('logout', () => {
  it('submits a top-level POST form carrying the CSRF token', () => {
    document.cookie = 'XSRF-TOKEN=token-xyz; path=/';
    const submit = vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});

    logout();

    const form = submit.mock.contexts[0] as HTMLFormElement;
    expect(form.method).toBe('post');
    expect(form.getAttribute('action')).toBe('/logout');
    expect((form.elements.namedItem('_csrf') as HTMLInputElement).value).toBe('token-xyz');
    submit.mockRestore();
  });
});
