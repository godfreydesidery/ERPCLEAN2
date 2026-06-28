/** Mirrors the backend RoleDto and PermissionDto. Every numeric id typed `string` (wire contract). */

/**
 * Mirrors ScreenReadGapDto from GET /api/v1/roles/uid/{uid}/read-closure-gaps.
 * Advisory only — never used to block a grant.
 */
export interface ScreenReadGap {
  /** Dotted screen key from screen-read-closure.json (e.g. "sales.record-receipt"). */
  screen: string;
  /** Permission code that guards the screen route (accessPermission). */
  accessPermission: string;
  /** Required closure-bearing read codes the role is missing. */
  missingReads: string[];
}

export interface Role {
  id: string;
  uid: string;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  status: string;
  permissionCodes: string[];
}

export interface Permission {
  id: string;
  code: string;
  module: string;
  description: string | null;
}

export interface CreateRoleRequest {
  code: string;
  name: string;
  description?: string;
}

export interface UpdateRoleRequest {
  name: string;
  description?: string;
}

export interface SetRolePermissionsRequest {
  permissionCodes: string[];
}
