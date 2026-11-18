import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { classifyApiError } from '../api/problems';

/**
 * A failed request, explained in plain language with the next step: reload (stale or protected data), sign in again
 * (session ended) or quote the reference to support (server/database refusals). Focused once when it appears so
 * screen-reader and keyboard users notice it. Never shows database messages: only the server's public text and code.
 */
export function ProblemMessage({
  error,
  prefix,
  forbiddenText,
  onReload,
}: {
  error: unknown;
  prefix?: string;
  forbiddenText?: string;
  onReload?: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const info = classifyApiError(error, forbiddenText);
  useEffect(() => {
    ref.current?.focus({ preventScroll: true });
  }, [error]);

  return (
    <div ref={ref} className={`error problem problem-${info.kind}`} role="alert" tabIndex={-1}>
      <p>
        {prefix && <strong>{prefix}: </strong>}
        {info.message}
        {info.code && <span className="muted small"> ({info.code})</span>}
      </p>
      {info.correlationId && (info.kind === 'server' || info.kind === 'protected') && (
        <p className="small">
          Reference: <code>{info.correlationId}</code>
        </p>
      )}
      {info.kind === 'unauthenticated' && (
        <button type="button" className="secondary" onClick={() => window.location.assign('/')}>
          Sign in again
        </button>
      )}
      {info.reload && onReload && (
        <button type="button" className="secondary" onClick={onReload}>
          Reload
        </button>
      )}
    </div>
  );
}

/** Loading indicator announced politely to assistive technology. */
export function Loading({ label }: { label: string }) {
  return (
    <p className="loading" role="status" aria-live="polite" aria-busy="true">
      <span className="spinner" aria-hidden="true" /> {label}…
    </p>
  );
}

/** Consistent "nothing here" message. */
export function EmptyState({ children }: { children: ReactNode }) {
  return <p className="muted empty-state">{children}</p>;
}

/**
 * Modal confirmation for decisions that cannot be undone (refund approval/rejection, dispute outcomes). Focus moves
 * to the dialog and stays inside it; Escape or Cancel closes it and returns focus to the element that opened it.
 */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  danger = false,
  busy = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const bodyId = useId();
  const dialog = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    cancel.current?.focus();
    return () => opener?.focus?.();
  }, []);

  function onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      event.preventDefault();
      onCancel();
      return;
    }
    if (event.key !== 'Tab' || !dialog.current) return;
    const focusable = Array.from(dialog.current.querySelectorAll<HTMLElement>('button:not([disabled])'));
    if (focusable.length === 0) return;
    const first = focusable[0]!;
    const last = focusable[focusable.length - 1]!;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="dialog-backdrop">
      <div
        ref={dialog}
        className="dialog card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={bodyId}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId}>{title}</h2>
        <div id={bodyId}>{children}</div>
        <div className="actions section-gap">
          <button ref={cancel} type="button" className="secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="button" className={danger ? 'danger' : undefined} onClick={onConfirm} disabled={busy}>
            {busy ? 'Saving…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
