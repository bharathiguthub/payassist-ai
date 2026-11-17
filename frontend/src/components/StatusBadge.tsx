import type { PaymentStatus } from '../api/types';
import { humanize } from '../format';

const TONE: Record<PaymentStatus, string> = {
  PENDING: 'neutral',
  AUTHORIZED: 'info',
  CAPTURED: 'info',
  SETTLED: 'success',
  FAILED: 'danger',
  REFUNDED: 'warning',
  DISPUTED: 'danger',
};

export function StatusBadge({ status }: { status: PaymentStatus }) {
  return <span className={`badge badge-${TONE[status]}`}>{humanize(status)}</span>;
}
