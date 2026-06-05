package com.erp.modules.iam.domain.dto;

/**
 * Auth response: the access token (+ its expiry epoch-seconds), the refresh token, and a thin
 * profile of who logged in and where (active company/branch uids; null when the user has no branch).
 */
public record TokenResponse(
        String accessToken,
        long accessTokenExpiresAt,
        String refreshToken,
        AuthUser user) {

    /** Minimal authenticated-user view for the client. */
    public record AuthUser(
            String uid,
            String username,
            String displayName,
            boolean isRoot,
            String activeCompanyUid,
            String activeBranchUid,
            boolean hasBranch) {
    }
}
