import type { DisputeView } from '../api/types';

export function disputeView(overrides: Partial<DisputeView> = {}): DisputeView {
  return {
    schemaVersion: '1.0',
    caseRef: 'DSP-0123456789AB',
    paymentRef: 'PAY-000072',
    reasonCategory: 'PRODUCT_NOT_RECEIVED',
    networkReasonCode: '13.1',
    disputedAmount: 706.68,
    currency: 'GBP',
    status: 'UNDER_INVESTIGATION',
    source: 'PAYASSIST',
    responseDueAt: '2026-12-01T00:00:00Z',
    overdue: false,
    openedBy: 'agent.alice',
    openedAt: '2026-11-16T10:00:00Z',
    reviewRequestedBy: null,
    updatedAt: '2026-11-16T11:00:00Z',
    history: [
      {
        sequenceNo: 1,
        eventType: 'OPENED',
        fromStatus: null,
        toStatus: 'OPEN',
        reasonCode: 'PRODUCT_NOT_RECEIVED',
        actor: 'agent.alice',
        correlationId: 'c1',
        occurredAt: '2026-11-16T10:00:00Z',
        paymentLedgerUpdated: true,
      },
    ],
    notes: [],
    evidence: [],
    pendingRefunds: [],
    allowedActions: ['ADD_NOTE', 'ADD_EVIDENCE'],
    version: 3,
    ...overrides,
  };
}
