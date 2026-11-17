import { useRef } from 'react';

function newKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `k-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * One Idempotency-Key per payload: retrying the same payload (e.g. after a timeout) reuses the key so the server
 * replays the original result instead of creating a duplicate; a different payload, or a reset after success,
 * gets a new key.
 */
export function useIdempotencyKey() {
  const attempt = useRef<{ payload: string; key: string } | null>(null);
  return {
    keyFor(payload: string): string {
      if (attempt.current?.payload !== payload) attempt.current = { payload, key: newKey() };
      return attempt.current.key;
    },
    reset(): void {
      attempt.current = null;
    },
  };
}
