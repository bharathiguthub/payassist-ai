import { ApiError } from './client';
import { classifyApiError, describeApiError } from './problems';

const problem = (status: number, extra: Record<string, unknown> = {}) =>
  new ApiError(status, { status, title: 'Title', detail: 'Server detail', ...extra } as never, 'cid-header-1');

describe('classifyApiError', () => {
  it.each([
    [401, {}, 'unauthenticated', false],
    [403, {}, 'forbidden', false],
    [403, { code: 'SELF_APPROVAL_FORBIDDEN' }, 'not-permitted', false],
    [404, {}, 'not-found', false],
    [409, { code: 'CONCURRENT_MODIFICATION' }, 'stale', true],
    [409, { code: 'RECORD_PROTECTED' }, 'protected', true],
    [422, { code: 'INVALID_AMOUNT_SCALE' }, 'invalid', false],
    [500, { code: 'DATABASE_PERMISSION_DENIED' }, 'server', false],
  ])('classifies HTTP %s %j as %s', (status, extra, kind, reload) => {
    const info = classifyApiError(problem(status, extra));
    expect(info.kind).toBe(kind);
    expect(info.reload).toBe(reload);
  });

  it('treats a request that never reached the server as a network problem', () => {
    expect(classifyApiError(new ApiError(0)).kind).toBe('network');
  });

  it('uses plain language for known codes and keeps the code', () => {
    const error = problem(403, { code: 'SELF_APPROVAL_FORBIDDEN' });
    expect(classifyApiError(error).message).toMatch(/Another supervisor must decide/);
    expect(describeApiError(error)).toMatch(/\(SELF_APPROVAL_FORBIDDEN\)$/);
  });

  it('never shows server internals for 5xx, but keeps the support reference', () => {
    const info = classifyApiError(problem(500, { detail: 'org.postgresql.util.PSQLException: relation x' }));
    expect(info.message).not.toMatch(/PSQL|relation/);
    expect(info.correlationId).toBe('cid-header-1');
  });

  it('prefers the correlation ID from the problem body', () => {
    const error = problem(409, { code: 'RECORD_PROTECTED', correlationId: 'cid-body-2' });
    expect(classifyApiError(error).correlationId).toBe('cid-body-2');
  });
});
