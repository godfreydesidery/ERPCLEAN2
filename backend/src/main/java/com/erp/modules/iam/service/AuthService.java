package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.MeResponse;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import java.util.List;

/**
 * Authentication: login, refresh (with single-use rotation + reuse detection), logout, and the
 * current-caller view ({@code me}). The security spine every other module logs in through
 * (ARCHITECTURE §4).
 */
public interface AuthService {

    TokenResponse login(String username, String rawPassword, String ip);

    TokenResponse refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    /** The current caller's identity + effective permission codes for the active scope (D-E). */
    MeResponse me();

    /** ADM-02: change the caller's own password (current password required); returns a new session. */
    TokenResponse changeOwnPassword(String currentPassword, String newPassword, String ip);

    /**
     * The caller's own ACTIVE branch assignments — the switchable branches for the shell selector
     * (ADR-0003 D-6). Self-scoped: reading your own branches needs only authentication, not USER.VIEW.
     */
    List<UserBranchDto> myBranches();
}
