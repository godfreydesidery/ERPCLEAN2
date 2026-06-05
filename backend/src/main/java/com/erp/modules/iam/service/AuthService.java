package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.TokenResponse;

/**
 * Authentication: login, refresh (with single-use rotation + reuse detection), logout. The
 * security spine every other module logs in through (ARCHITECTURE §4). One responsibility — issuing
 * and revoking sessions.
 */
public interface AuthService {

    TokenResponse login(String username, String rawPassword);

    TokenResponse refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);
}
