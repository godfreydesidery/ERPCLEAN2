package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.MeResponse;
import com.erp.modules.iam.domain.dto.TokenResponse;

/**
 * Authentication: login, refresh (with single-use rotation + reuse detection), logout, and the
 * current-caller view ({@code me}). The security spine every other module logs in through
 * (ARCHITECTURE §4).
 */
public interface AuthService {

    TokenResponse login(String username, String rawPassword);

    TokenResponse refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);

    /** The current caller's identity + effective permission codes for the active scope (D-E). */
    MeResponse me();
}
