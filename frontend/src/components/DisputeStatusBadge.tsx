import type { DisputeStatus } from '../api/types';
import { humanize } from '../format';

const TONE: Record<DisputeStatus, string> = {
  OPEN: 'warning',
  UNDER_INVESTIGATION: 'info',
  PENDING_REVIEW: 'info',
  EVIDENCE_SUBMITTED: 'info',
  WON: 'success',
  LOST: 'danger',
  WITHDRAWN: 'neutral',
};

/** Case status (investigation workflow), not the payment's financial status. */
export function DisputeStatusBadge({ status }: { status: DisputeStatus }) {
  return <span className={`badge badge-${TONE[status]}`}>{humanize(status)}</span>;
}

export function OverdueBadge({ overdue }: { overdue: boolean }) {
  return overdue ? <span className="badge badge-danger">Overdue</span> : null;
}
