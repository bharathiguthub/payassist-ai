import { ApiError } from './client';

/**
 * How the UI should treat a failed request. The backend stays the source of truth: this only decides what to tell
 * the user and which next step to offer (reload, sign in again, contact support with the reference).
 */
export type ProblemKind =
  | 'unauthenticated' // 401: the session ended
  | 'forbidden' // 403 without a code: the role does not allow it
  | 'not-permitted' // 403 with a code: a business rule (e.g. separation of duties) refused it
  | 'not-found' // 404
  | 'stale' // 409: someone else changed it, or it is no longer in a state that allows this
  | 'protected' // 409 RECORD_PROTECTED: the database refused the change
  | 'invalid' // 400 / 422: the input or a business rule
  | 'network' // no response at all
  | 'server'; // 5xx

export interface ProblemInfo {
  kind: ProblemKind;
  message: string;
  code?: string;
  correlationId?: string;
  /** The data on screen may be outdated: offer a reload. */
  reload: boolean;
}

/** Plain-language messages for stable codes where the server text is too technical for care staff. */
const CODE_MESSAGES: Record<string, string> = {
  SELF_APPROVAL_FORBIDDEN: 'You cannot decide on a refund you requested yourself. Another supervisor must decide.',
  SELF_REJECTION_FORBIDDEN: 'You cannot decide on a refund you requested yourself. Another supervisor must decide.',
  SELF_REVIEW_FORBIDDEN: 'You cannot approve a review you requested yourself. Another supervisor must approve it.',
  SELF_LIABILITY_FORBIDDEN: 'You cannot accept liability on a case you opened. Another supervisor must do it.',
  APPROVAL_LIMIT_EXCEEDED: 'This refund is above your approval limit and needs escalation.',
  CONCURRENT_MODIFICATION: 'Someone else changed this record while you were working on it. It has been reloaded; check it and try again.',
  INVALID_STATE_TRANSITION: 'This action is no longer possible in the current state. The record has been reloaded.',
  RECORD_PROTECTED: 'This record is protected and can no longer be changed this way (for example, a final decision). It has been reloaded.',
  DATABASE_PERMISSION_DENIED: 'The server is not allowed to make this change. Contact support and quote the reference below.',
  CARD_NUMBER_DETECTED: 'The text looks like it contains a card number. Remove it and try again.',
  EMAIL_ADDRESS_DETECTED: 'The text contains an email address. Refer to the customer by reference instead.',
};

const STALE_CODES = new Set(['CONCURRENT_MODIFICATION', 'INVALID_STATE_TRANSITION', 'DISPUTE_CASE_CLOSED']);

export function classifyApiError(error: unknown, forbiddenText = 'You do not have permission to do this.'): ProblemInfo {
  if (!(error instanceof ApiError)) {
    return { kind: 'server', message: error instanceof Error ? error.message : 'Unexpected error', reload: false };
  }
  const code = error.problem?.code;
  const serverText = error.problem?.detail ?? error.problem?.title ?? error.message;
  const base = { code, correlationId: error.correlationId };
  const text = (code && CODE_MESSAGES[code]) ?? serverText;
  switch (true) {
    case error.status === 0:
      return { ...base, kind: 'network', message: 'PayAssist could not reach the server. Check your connection and try again.', reload: false };
    case error.status === 401:
      return { ...base, kind: 'unauthenticated', message: 'Your session has ended. Sign in again to continue.', reload: false };
    case error.status === 403 && !code:
      return { ...base, kind: 'forbidden', message: forbiddenText, reload: false };
    case error.status === 403:
      return { ...base, kind: 'not-permitted', message: text, reload: false };
    case error.status === 404:
      return { ...base, kind: 'not-found', message: serverText, reload: false };
    case code === 'RECORD_PROTECTED':
      return { ...base, kind: 'protected', message: text, reload: true };
    case error.status === 409:
      return { ...base, kind: 'stale', message: text, reload: true };
    case error.status === 400 || error.status === 422:
      return { ...base, kind: 'invalid', message: text, reload: !!code && STALE_CODES.has(code) };
    default:
      return {
        ...base,
        kind: 'server',
        message: (code && CODE_MESSAGES[code]) ?? 'Something went wrong on the server. Try again; if it keeps happening, contact support and quote the reference below.',
        reload: false,
      };
  }
}

/** Human-readable text for an API error, including the business-rule code when present. */
export function describeApiError(error: unknown, forbiddenText = 'You do not have permission to do this.'): string {
  const info = classifyApiError(error, forbiddenText);
  return info.code ? `${info.message} (${info.code})` : info.message;
}
