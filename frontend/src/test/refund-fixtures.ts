import type { RefundRequestView } from '../api/types';

export function refundRequest(overrides: Partial<RefundRequestView> = {}): RefundRequestView {
  return {
    schemaVersion: '1.0',
    refundRef: 'RFD-0123456789AB',
    paymentRef: 'PAY-000001',
    amount: 25.5,
    currency: 'USD',
    reason: 'CUSTOMER_REQUEST',
    status: 'PENDING_APPROVAL',
    eligibility: { decision: 'ELIGIBLE', reasonCodes: ['WITHIN_POLICY'], policyVersion: 'SYNTHETIC-REFUND-POLICY-2026.11-v1' },
    requestedBy: 'agent.alice',
    requestedAt: '2026-11-16T12:00:00Z',
    correlationId: 'corr-1',
    decision: null,
    allowedActions: ['APPROVE', 'REJECT'],
    version: 0,
    ...overrides,
  };
}
