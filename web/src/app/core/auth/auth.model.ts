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

// ── Manager step-up ("supervisor override") ───────────────────────────────────

/**
 * POST /api/v1/auth/verify-authority — the supervisor types their OWN username + password on the
 * operator's device to authorise one action. Sent with the OPERATOR's normal bearer token; the
 * operator's session is untouched (no token is minted, nothing is rotated), so a cashier stays
 * logged in mid-sale.
 *
 * `permissionCode` must be a real seeded code — the server refuses anything else, so a typo can
 * never read as "approved".
 */
export interface VerifyAuthorityRequest {
  username: string;
  password: string;
  permissionCode: string;
}

/**
 * The outcome of a step-up check. A refusal is HTTP **200** with `authorised: false` and every
 * authoriser field null — branch on `authorised`, never on the status code, so the manager can
 * retype without the caller unwinding what it was doing.
 */
export interface AuthorityVerificationDto {
  authorised: boolean;
  permissionCode: string;
  /** The approving user's uid — carry this into the business request. Null when refused. */
  authoriserUid: string | null;
  authoriserUsername: string | null;
  authoriserName: string | null;
  /** Friendly, non-disclosing explanation — safe to show verbatim. */
  message: string;
}
