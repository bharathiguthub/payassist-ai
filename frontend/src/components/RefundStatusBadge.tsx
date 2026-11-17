import type { RefundRequestStatus } from '../api/types';
import { humanize } from '../format';

const TONE: Record<RefundRequestStatus, string> = {
  PENDING_APPROVAL: 'info',
  ESCALATION_REQUIRED: 'warning',
  APPROVED: 'success',
  REJECTED: 'neutral',
};

export function RefundStatusBadge({ status }: { status: RefundRequestStatus }) {
  return <span className={`badge badge-${TONE[status]}`}>{humanize(status)}</span>;
}
