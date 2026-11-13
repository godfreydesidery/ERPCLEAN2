/** Tenant subscription plans (ADR-0069). Mirrors the backend DTOs; BigDecimal amounts are numbers. */

export type PlanCode = 'BASIC' | 'STANDARD' | 'PREMIUM' | 'ENTERPRISE';
export type SubscriptionStage =
  | 'UNRESTRICTED'
  | 'TRIAL'
  | 'ACTIVE'
  | 'GRACE'
  | 'READ_ONLY'
  | 'SUSPENDED';
export type BillingCycle = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'ANNUAL' | 'CUSTOM';
export type CapResource = 'USERS' | 'BRANCHES' | 'TILLS' | 'COMPANIES';

export interface ModuleRef {
  code: string;
  name: string;
}

/** GET /subscription-plans. A null max is unlimited. */
export interface PlanCatalogue {
  plans: PlanDefinition[];
  addOns: { code: 'RESTAURANT' | 'ORBIXHQ'; name: string }[];
}

export interface PlanDefinition {
  code: PlanCode;
  name: string;
  rank: number;
  maxUsers: number | null;
  maxBranches: number | null;
  maxTills: number | null;
  maxCompanies: number | null;
  modules: ModuleRef[];
}

export interface CapUsage {
  resource: CapResource;
  planLimit: number | null;
  override: number | null;
  effectiveLimit: number | null;
  inUse: number;
}

export interface SubscriptionPayment {
  uid: string;
  amount: number;
  currencyCode: string;
  paidOn: string;
  paidUntil: string;
  reference: string | null;
  note: string | null;
  /** ISO instant. */
  recordedAt: string;
  recordedBy: string | null;
}

/** GET/PUT /organisations/uid/{uid}/subscription. */
export interface TenantSubscription {
  organisationUid: string;
  organisationName: string;
  /** False: no plan yet, so the tenant is unrestricted. */
  assigned: boolean;
  planCode: PlanCode | null;
  planName: string | null;
  addOnRestaurant: boolean;
  addOnOrbixHq: boolean;
  billingCycle: BillingCycle | null;
  trialEndsOn: string | null;
  paidUntil: string | null;
  graceDays: number;
  readOnlyDays: number;
  notes: string | null;
  stage: SubscriptionStage;
  dueDate: string | null;
  readOnlyFrom: string | null;
  suspendedFrom: string | null;
  caps: CapUsage[];
  modules: ModuleRef[];
  payments: SubscriptionPayment[];
}

export interface SaveSubscriptionRequest {
  planCode: PlanCode;
  addOnRestaurant: boolean;
  addOnOrbixHq: boolean;
  billingCycle: BillingCycle;
  trialEndsOn: string | null;
  paidUntil: string | null;
  graceDays: number;
  readOnlyDays: number;
  maxUsersOverride: number | null;
  maxBranchesOverride: number | null;
  maxTillsOverride: number | null;
  maxCompaniesOverride: number | null;
  notes: string | null;
}

export interface RecordPaymentRequest {
  amount: number;
  currencyCode: string;
  paidOn: string;
  paidUntil: string;
  reference: string | null;
  note: string | null;
}

/** The slice of the subscription every tenant user sees (GET /auth/me). */
export interface SubscriptionSummary {
  planCode: PlanCode;
  planName: string;
  stage: SubscriptionStage;
  dueDate: string | null;
  readOnlyFrom: string | null;
  suspendedFrom: string | null;
  addOnRestaurant: boolean;
  addOnOrbixHq: boolean;
}

export const BILLING_CYCLES: { code: BillingCycle; label: string }[] = [
  { code: 'MONTHLY', label: 'Monthly' },
  { code: 'QUARTERLY', label: 'Quarterly' },
  { code: 'HALF_YEARLY', label: 'Half-yearly' },
  { code: 'ANNUAL', label: 'Annual' },
  { code: 'CUSTOM', label: 'Custom' },
];

export const CAP_LABELS: Record<CapResource, string> = {
  USERS: 'Users',
  BRANCHES: 'Branches',
  TILLS: 'POS tills',
  COMPANIES: 'Companies',
};

export function stageLabel(stage: SubscriptionStage): string {
  switch (stage) {
    case 'TRIAL': return 'Trial';
    case 'ACTIVE': return 'Active';
    case 'GRACE': return 'Payment overdue';
    case 'READ_ONLY': return 'Read-only';
    case 'SUSPENDED': return 'Suspended';
    default: return 'Unrestricted';
  }
}

/** status-tag modifier for a stage. */
export function stageTagClass(stage: SubscriptionStage): string {
  switch (stage) {
    case 'TRIAL': return 'status-tag--info';
    case 'ACTIVE': return 'status-tag--ok';
    case 'GRACE': return 'status-tag--warn';
    case 'READ_ONLY':
    case 'SUSPENDED': return 'status-tag--danger';
    default: return 'status-tag--neutral';
  }
}

// ── Local-date helpers (yyyy-MM-dd strings; no time zone drift) ──────────────

const pad = (n: number): string => String(n).padStart(2, '0');

/** Today in the browser's local calendar, as yyyy-MM-dd. */
export function localToday(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function parseIso(iso: string): { y: number; m: number; d: number } {
  const [y, m, d] = iso.split('-').map(Number);
  return { y, m, d };
}

/** Whole days from `from` to `to` (both yyyy-MM-dd). Negative when `to` is earlier. */
export function daysBetween(from: string, to: string): number {
  const a = parseIso(from);
  const b = parseIso(to);
  return Math.round((Date.UTC(b.y, b.m - 1, b.d) - Date.UTC(a.y, a.m - 1, a.d)) / 86_400_000);
}

/** Adds calendar months, clamping the day to the end of a shorter month. */
export function addMonths(iso: string, months: number): string {
  const { y, m, d } = parseIso(iso);
  const total = y * 12 + (m - 1) + months;
  const ny = Math.floor(total / 12);
  const nm = (total % 12) + 1;
  const last = new Date(Date.UTC(ny, nm, 0)).getUTCDate();
  return `${ny}-${pad(nm)}-${pad(Math.min(d, last))}`;
}

/** The suggested new paid-until: `from` plus one billing cycle. Null for CUSTOM or no cycle. */
export function addCycle(from: string, cycle: BillingCycle | null): string | null {
  switch (cycle) {
    case 'MONTHLY': return addMonths(from, 1);
    case 'QUARTERLY': return addMonths(from, 3);
    case 'HALF_YEARLY': return addMonths(from, 6);
    case 'ANNUAL': return addMonths(from, 12);
    default: return null;
  }
}
