/**
 * Response from GET /auth/me — the caller's full profile including effective permissions for the
 * active company/branch context.
 */
export interface MeResponse {
  uid: string;
  username: string;
  displayName: string;
  isRoot: boolean;
  activeCompanyUid: string | null;
  activeBranchUid: string | null;
  permissions: string[];
}

/** Mirrors the backend TokenResponse. ids on the wire are strings; here uids are the identifiers. */
export interface AuthUser {
  uid: string;
  username: string;
  displayName: string;
  isRoot: boolean;
  activeCompanyUid: string | null;
  activeBranchUid: string | null;
  hasBranch: boolean;
}

export interface TokenResponse {
  accessToken: string;
  accessTokenExpiresAt: number;
  refreshToken: string;
  user: AuthUser;
}

export interface LoginRequest {
  username: string;
  password: string;
}
