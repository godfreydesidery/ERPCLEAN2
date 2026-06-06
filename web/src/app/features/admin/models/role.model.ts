/** Mirrors the backend RoleDto and PermissionDto. Every numeric id typed `string` (wire contract). */

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
