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
  /** Stable business-rule code (e.g. INVALID_AMOUNT_SCALE), present on 403/409/422 business-rule responses. */
  code?: string;
  /** Eligibility reason codes behind REFUND_NOT_ELIGIBLE / REFUND_NO_LONGER_ELIGIBLE. */
  reasons?: string[];
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
  | 'DISPUTE_WITHDRAWN'
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
    /** OPEN is an investigation status: nothing has been reversed or charged back. */
    state: 'NONE' | 'OPEN' | 'WON' | 'LOST' | 'WITHDRAWN';
    reasonCode: string | null;
    reasonDescription: string | null;
    openedAt: string | null;
    closedAt: string | null;
  };
  history: { eventCount: number; complete: boolean; recordedFrom: string | null };
  findings: Finding[];
  possibleNextEvents: PaymentEventType[];
  /** The active dispute case, if any. */
  disputeCase: {
    caseRef: string;
    status: DisputeStatus;
    reasonCategory: DisputeReasonCategory;
    openedAt: string;
    responseDueAt: string | null;
    overdue: boolean;
  } | null;
}

// --- Phase 2: refund eligibility ------------------------------------------------------------------

export type EligibilityDecision = 'ELIGIBLE' | 'MANUAL_REVIEW' | 'INELIGIBLE';
export type EligibilityOutcome = 'PASS' | 'MANUAL_REVIEW' | 'INELIGIBLE' | 'NOT_EVALUATED';

export interface EligibilityReason {
  code: string;
  outcome: EligibilityOutcome;
  message: string;
}

export interface EligibilityCheck {
  rule: string;
  outcome: EligibilityOutcome;
  code: string | null;
  message: string;
}

export interface RefundEligibility {
  schemaVersion: string;
  decision: EligibilityDecision;
  paymentRef: string;
  requestedAmount: number;
  currency: string;
  paymentStatus: PaymentStatus;
  paymentMethod: PaymentMethod;
  paymentAmount: number;
  paymentCurrency: string;
  previouslyRefundedAmount: number;
  reservedPendingAmount: number;
  remainingRefundableAmount: number;
  refundWindow: { basis: string; startsAt: string; endsAt: string; days: number; open: boolean };
  reasons: EligibilityReason[];
  checks: EligibilityCheck[];
  policyVersion: string;
  evaluatedAt: string;
  paymentVersion: number;
  correlationId: string | null;
  advisory: string;
}

/** Request body. The amount is a decimal string so no binary floating point ever touches money. */
export interface EligibilityCheckRequest {
  paymentRef: string;
  amount?: string;
  currency?: string;
}

// --- Phase 2: refund requests and supervisor approval ---------------------------------------------

export const REFUND_REASONS = [
  'CUSTOMER_REQUEST',
  'DUPLICATE_CHARGE',
  'PRODUCT_NOT_RECEIVED',
  'NOT_AS_DESCRIBED',
  'SERVICE_NOT_PROVIDED',
  'GOODWILL',
] as const;
export type RefundReason = (typeof REFUND_REASONS)[number];

export const REJECTION_REASONS = [
  'OUTSIDE_POLICY',
  'INSUFFICIENT_EVIDENCE',
  'DUPLICATE_REQUEST',
  'CUSTOMER_WITHDREW',
  'SUSPECTED_FRAUD',
  'ESCALATION_NOT_AVAILABLE',
] as const;
export type RejectionReason = (typeof REJECTION_REASONS)[number];

export type RefundRequestStatus = 'PENDING_APPROVAL' | 'ESCALATION_REQUIRED' | 'APPROVED' | 'REJECTED';

export interface RefundRequestView {
  schemaVersion: string;
  refundRef: string;
  paymentRef: string;
  amount: number;
  currency: string;
  reason: RefundReason;
  status: RefundRequestStatus;
  eligibility: { decision: EligibilityDecision; reasonCodes: string[]; policyVersion: string };
  requestedBy: string;
  requestedAt: string;
  correlationId: string | null;
  decision: {
    outcome: RefundRequestStatus;
    reasonCode: string;
    decidedBy: string;
    decidedAt: string;
    correlationId: string | null;
    ledgerEventRecorded: boolean;
  } | null;
  /** What the current user may do next. A UI hint: the server re-checks every action. */
  allowedActions: ('APPROVE' | 'REJECT')[];
  version: number;
}

/** The amount is a decimal string so no binary floating point ever touches money. */
export interface CreateRefundRequestBody {
  paymentRef: string;
  amount: string;
  currency: string;
  reason: RefundReason;
}

// --- Phase 2: dispute cases ------------------------------------------------------------------------

export const DISPUTE_STATUSES = [
  'OPEN',
  'UNDER_INVESTIGATION',
  'PENDING_REVIEW',
  'EVIDENCE_SUBMITTED',
  'WON',
  'LOST',
  'WITHDRAWN',
] as const;
export type DisputeStatus = (typeof DISPUTE_STATUSES)[number];

export const DISPUTE_REASON_CATEGORIES = [
  'FRAUDULENT',
  'PRODUCT_NOT_RECEIVED',
  'NOT_AS_DESCRIBED',
  'DUPLICATE_CHARGE',
  'CREDIT_NOT_PROCESSED',
  'SUBSCRIPTION_CANCELLED',
  'OTHER',
] as const;
export type DisputeReasonCategory = (typeof DISPUTE_REASON_CATEGORIES)[number];

export const EVIDENCE_TYPES = [
  'PROOF_OF_DELIVERY',
  'CUSTOMER_COMMUNICATION',
  'TRANSACTION_RECEIPT',
  'TERMS_AND_POLICIES',
  'SERVICE_USAGE_RECORD',
  'REFUND_OR_CREDIT_RECORD',
  'OTHER',
] as const;
export type EvidenceType = (typeof EVIDENCE_TYPES)[number];

export const RETURN_REASONS = ['INSUFFICIENT_EVIDENCE', 'NEEDS_CLARIFICATION', 'MISSING_DOCUMENTS'] as const;
export const WITHDRAWAL_REASONS = ['CUSTOMER_WITHDREW', 'RESOLVED_WITH_CUSTOMER', 'RAISED_IN_ERROR'] as const;

export type DisputeAction =
  | 'ADD_NOTE'
  | 'ADD_EVIDENCE'
  | 'START_INVESTIGATION'
  | 'REQUEST_REVIEW'
  | 'RETURN_FOR_INVESTIGATION'
  | 'APPROVE_SUBMISSION'
  | 'RECORD_OUTCOME'
  | 'ACCEPT_LIABILITY'
  | 'WITHDRAW';

export interface DisputeSummary {
  caseRef: string;
  paymentRef: string;
  reasonCategory: DisputeReasonCategory;
  disputedAmount: number;
  currency: string;
  status: DisputeStatus;
  source: 'PAYASSIST' | 'CARD_NETWORK';
  /** As provided by the user from the bank/network notification; PayAssist never computes deadlines. */
  responseDueAt: string | null;
  overdue: boolean;
  openedBy: string;
  openedAt: string;
  reviewRequestedBy: string | null;
  version: number;
}

export interface DisputeView extends DisputeSummary {
  schemaVersion: string;
  networkReasonCode: string | null;
  updatedAt: string;
  history: {
    sequenceNo: number;
    eventType: string;
    fromStatus: DisputeStatus | null;
    toStatus: DisputeStatus | null;
    reasonCode: string | null;
    actor: string;
    correlationId: string | null;
    occurredAt: string;
    paymentLedgerUpdated: boolean;
  }[];
  /** Plain text. Always render as text, never as HTML. */
  notes: { body: string; author: string; createdAt: string }[];
  evidence: {
    type: EvidenceType;
    documentReference: string;
    description: string | null;
    receivedAt: string | null;
    addedBy: string;
    addedAt: string;
  }[];
  pendingRefunds: { refundRef: string; amount: number; currency: string; status: string }[];
  allowedActions: DisputeAction[];
}
