/**
 * OtherParty models — mirrors backend DTOs exactly (ADR-0006 D-10/D-11).
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 */

import { PartyType, MasterStatus } from '../../models/party.model';

// Re-export for convenience within the feature.
export type { PartyType, MasterStatus };

// ── OtherParty — mirrors OtherPartyDto ───────────────────────────────────────

export interface OtherPartyModel {
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
  otherKind: string | null;
  status: MasterStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── CreateOtherPartyRequest — mirrors backend record ─────────────────────────

export interface CreateOtherPartyRequest {
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
  otherKind?: string;
}

// ── UpdateOtherPartyRequest — mirrors backend record ─────────────────────────

export interface UpdateOtherPartyRequest {
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
  otherKind?: string;
}
