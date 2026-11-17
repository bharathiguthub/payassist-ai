import { ApiError } from './client';

/** Human-readable text for an API error, including the business-rule code and reasons when present. */
export function describeApiError(error: unknown, forbiddenText = 'You do not have permission to do this.'): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : 'Unexpected error';
  const problem = error.problem;
  if (error.status === 403 && !problem?.code) return forbiddenText;
  const parts = [problem?.detail ?? problem?.title ?? error.message];
  if (problem?.code) parts.push(`(${problem.code})`);
  return parts.join(' ');
}
