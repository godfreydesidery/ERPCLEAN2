/**
 * Shared party models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 * Money follows ADR-0005 D-7: { amount: string; currency: string }.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type PartyType = 'INDIVIDUAL' | 'BUSINESS';
export type CustomerKind = 'CASH_WALK_IN' | 'CREDIT_ACCOUNT';
export type SupplierKind = 'GOODS' | 'SERVICE';
export type AgentKind = 'INTERNAL' | 'EXTERNAL';
export type MasterStatus = 'ACTIVE' | 'ARCHIVED';

// ── Money (ADR-0005 D-7) ──────────────────────────────────────────────────────

export interface Money {
  amount: string;
  currency: string;
}

// ── PartyBranch — mirrors PartyBranchDto ─────────────────────────────────────

/**
 * Mirrors backend PartyBranchDto.
 * branchId is a string on the wire (Long-as-string global Jackson config).
 */
export interface PartyBranch {
  branchId: string;
  assignedAt: string | null;
  assignedBy: string | null;
}

export interface AssignPartyBranchRequest {
  branchUid: string;
}

// ── Customer — mirrors CustomerDto ───────────────────────────────────────────

export interface CustomerModel {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  partyType: PartyType;
  displayName: string;
  legalName: string | null;
  tin: string | null;
  vatRegistered: boolean;
  vrn: string | null;
  businessRegNo: string | null;
  mobileMoneyNo: string | null;
  phone: string | null;
  email: string | null;
  physicalAddress: string | null;
  postalAddress: string | null;
  region: string | null;
  district: string | null;
  customerKind: CustomerKind;
  creditLimit: Money | null;
  paymentTermsDays: number | null;
  status: MasterStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateCustomerRequest {
  companyId: string;
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  customerKind: CustomerKind;
  creditLimit?: Money;
  paymentTermsDays?: number;
}

export interface UpdateCustomerRequest {
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  customerKind: CustomerKind;
  creditLimit?: Money;
  paymentTermsDays?: number;
}

// ── Supplier — mirrors SupplierDto ───────────────────────────────────────────

export interface SupplierModel {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  partyType: PartyType;
  displayName: string;
  legalName: string | null;
  tin: string | null;
  vatRegistered: boolean;
  vrn: string | null;
  businessRegNo: string | null;
  mobileMoneyNo: string | null;
  phone: string | null;
  email: string | null;
  physicalAddress: string | null;
  postalAddress: string | null;
  region: string | null;
  district: string | null;
  supplierKind: SupplierKind;
  status: MasterStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateSupplierRequest {
  companyId: string;
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  supplierKind: SupplierKind;
}

export interface UpdateSupplierRequest {
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  supplierKind: SupplierKind;
}

// ── Agent — mirrors AgentDto ─────────────────────────────────────────────────

export interface AgentModel {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  partyType: PartyType;
  displayName: string;
  legalName: string | null;
  tin: string | null;
  vatRegistered: boolean;
  vrn: string | null;
  businessRegNo: string | null;
  mobileMoneyNo: string | null;
  phone: string | null;
  email: string | null;
  physicalAddress: string | null;
  postalAddress: string | null;
  region: string | null;
  district: string | null;
  agentKind: AgentKind;
  /** appUserId is stringified on the wire (Long-as-string). */
  appUserId: string | null;
  status: MasterStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface CreateAgentRequest {
  companyId: string;
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  agentKind: AgentKind;
  /** Send as numeric string; Jackson accepts "42" or 42. Null for EXTERNAL agents. */
  appUserId?: string;
}

export interface UpdateAgentRequest {
  partyType: PartyType;
  displayName: string;
  legalName?: string;
  tin?: string;
  vatRegistered?: boolean;
  vrn?: string;
  businessRegNo?: string;
  mobileMoneyNo?: string;
  phone?: string;
  email?: string;
  physicalAddress?: string;
  postalAddress?: string;
  region?: string;
  district?: string;
  agentKind: AgentKind;
  appUserId?: string;
}
