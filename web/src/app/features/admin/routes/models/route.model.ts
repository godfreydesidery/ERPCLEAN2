/**
 * Routes feature models — mirrors backend DTOs exactly.
 * All Long id fields are typed `string` (wire contract: Jackson stringifies Longs).
 */

// ── Enums ──────────────────────────────────────────────────────────────────────

export type RouteStatus = 'ACTIVE' | 'INACTIVE' | 'ARCHIVED';

// ── RouteDto ──────────────────────────────────────────────────────────────────

export interface RouteDto {
  id: string;
  uid: string;
  companyId: string;
  code: string;
  name: string;
  locationIdentifier: string | null;
  status: RouteStatus;
  version: string | null;
  createdAt: string | null;
  createdBy: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
}

// ── Assignment DTOs ───────────────────────────────────────────────────────────

export interface RouteCustomerDto {
  routeId: string;
  customerId: string;
  customerUid: string;
  customerCode: string;
  customerName: string;
  assignedAt: string | null;
  assignedBy: string | null;
}

export interface RouteAgentDto {
  routeId: string;
  agentId: string;
  agentUid: string;
  agentCode: string;
  agentName: string;
  isPrimary: boolean;
  assignedAt: string | null;
  assignedBy: string | null;
}

export interface RouteBranchDto {
  routeId: string;
  branchId: string;
  branchUid: string;
  branchCode: string;
  branchName: string;
  assignedAt: string | null;
  assignedBy: string | null;
}

// ── Request types ─────────────────────────────────────────────────────────────

export interface CreateRouteRequest {
  companyUid: string;
  name: string;
  locationIdentifier?: string;
}

export interface UpdateRouteRequest {
  name: string;
  locationIdentifier?: string;
}

export interface AssignRouteCustomerRequest {
  customerUid: string;
}

export interface AssignRouteAgentRequest {
  agentUid: string;
  isPrimary: boolean;
}

export interface AssignRouteBranchRequest {
  branchUid: string;
}
