// Mirrors the backend /api/v1 contract.

export type Role = 'CARE_AGENT' | 'CARE_SUPERVISOR' | 'ADMIN';

export const PAYMENT_STATUSES = [
  'PENDING',
  'AUTHORIZED',
  'CAPTURED',
  'SETTLED',
  'FAILED',
  'VOIDED',
  'PARTIALLY_REFUNDED',
  'REFUNDED',
  'DISPUTED',
  'CHARGED_BACK',
] as const;
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];

export type PaymentMethod = 'CARD' | 'BANK_TRANSFER' | 'WALLET';

export interface CurrentUser {
  username: string;
  displayName: string | null;
  email: string | null;
  roles: Role[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PaymentListItem {
  paymentRef: string;
  customerRef: string | null;
  customerName: string | null;
  amount: number;
  currency: string;
  status: PaymentStatus;
  paymentMethod: PaymentMethod;
  merchantName: string;
  createdAt: string;
}

export interface CustomerSummary {
  id: string;
  customerRef: string;
  fullName: string;
  maskedEmail: string;
  countryCode: string;
}

export interface PaymentDetails {
  paymentRef: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  paymentMethod: PaymentMethod;
  merchantName: string;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  customer: CustomerSummary | null;
}

export interface PaymentStats {
  totalPayments: number;
  byStatus: { status: PaymentStatus; count: number }[];
}

export interface AuditEvent {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  resourceType: string;
  resourceId: string;
}

/** RFC 9457 Problem Details returned by the backend on errors. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
}

// --- Phase 2: payment timeline and investigation summary -------------------------------------

export type PaymentEventType =
  | 'LEGACY_SNAPSHOT'
  | 'CREATED'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'SETTLED'
  | 'FAILED'
  | 'VOIDED'
  | 'REFUND_RECORDED'
  | 'DISPUTE_OPENED'
  | 'DISPUTE_WON'
  | 'DISPUTE_LOST'
  | 'REFUND_REQUESTED'
  | 'REFUND_REJECTED'
  | 'REFUND_CANCELLED'
  | 'REFUND_ESCALATED'
  | 'DISPUTE_UPDATED';

export type PaymentEventKind = 'LIFECYCLE' | 'INFORMATIONAL' | 'SYSTEM_ONLY';
export type PaymentEventSource = 'PROCESSOR' | 'CARD_NETWORK' | 'PAYASSIST' | 'MIGRATION';

export interface TimelineEntry {
  sequenceNo: number;
  eventType: PaymentEventType;
  kind: PaymentEventKind;
  description: string;
  fromStatus: PaymentStatus | null;
  toStatus: PaymentStatus | null;
  amount: number | null;
  currency: string | null;
  reasonCode: string | null;
  reasonDescription: string | null;
  actor: string;
  source: PaymentEventSource;
  correlationId: string | null;
  occurredAt: string;
  recordedAt: string;
  recordedOutOfOrder: boolean;
}

export interface PaymentTimeline {
  paymentRef: string;
  currentStatus: PaymentStatus;
  currentStatusDescription: string;
  amount: number;
  currency: string;
  eventCount: number;
  events: TimelineEntry[];
}

export type FindingSeverity = 'WARNING' | 'INFO';

export interface Finding {
  code: string;
  severity: FindingSeverity;
  message: string;
  evidence: number[];
}

export interface InvestigationSummary {
  schemaVersion: string;
  paymentRef: string;
  generatedAt: string;
  paymentVersion: number;
  basis: string;
  payment: {
    amount: number;
    currency: string;
    status: PaymentStatus;
    statusDescription: string;
    terminal: boolean;
    paymentMethod: PaymentMethod;
    merchantName: string;
    failureReason: string | null;
    failureDescription: string | null;
    createdAt: string;
    lastUpdatedAt: string;
    customer: CustomerSummary | null;
  };
  milestones: Record<
    | 'createdAt'
    | 'authorizedAt'
    | 'capturedAt'
    | 'settledAt'
    | 'failedAt'
    | 'voidedAt'
    | 'lastRefundAt'
    | 'disputeOpenedAt'
    | 'disputeClosedAt',
    string | null
  >;
  money: {
    currency: string;
    paymentAmount: number;
    refundedAmount: number;
    notRefundedAmount: number;
    refundCount: number;
  };
  dispute: {
    state: 'NONE' | 'OPEN' | 'WON' | 'LOST';
    reasonCode: string | null;
    reasonDescription: string | null;
    openedAt: string | null;
    closedAt: string | null;
  };
  history: { eventCount: number; complete: boolean; recordedFrom: string | null };
  findings: Finding[];
  possibleNextEvents: PaymentEventType[];
}
