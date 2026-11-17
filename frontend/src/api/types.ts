// Mirrors the backend /api/v1 contract.

export type Role = 'CARE_AGENT' | 'CARE_SUPERVISOR' | 'ADMIN';

export const PAYMENT_STATUSES = [
  'PENDING',
  'AUTHORIZED',
  'CAPTURED',
  'SETTLED',
  'FAILED',
  'REFUNDED',
  'DISPUTED',
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
