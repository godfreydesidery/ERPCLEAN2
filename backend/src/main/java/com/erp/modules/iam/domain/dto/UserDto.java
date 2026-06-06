package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.AppUser;
import java.time.Instant;

/**
 * Response shape for an application user (DATA-MODEL §1.4). Carries both {@code id} (numeric,
 * serialised as a JSON string globally) and {@code uid} (the URL identifier).
 *
 * <p>{@code locked} is {@code true} when the lockout window has not yet expired (i.e. the account
 * is temporarily locked due to failed login attempts). {@code lastLoginAt} is the ISO-8601 string
 * of the last successful login, or {@code null} if the user has never logged in.
 */
public record UserDto(
        Long id,
        String uid,
        String username,
        String displayName,
        String email,
        String phone,
        boolean isRoot,
        String status,
        boolean locked,
        String lastLoginAt) {

    public static UserDto from(AppUser u) {
        Instant now = Instant.now();
        String lastLogin = u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null;
        return new UserDto(
                u.getId(),
                u.getUid(),
                u.getUsername(),
                u.getDisplayName(),
                u.getEmail(),
                u.getPhone(),
                u.isRoot(),
                u.getStatus().name(),
                u.isLocked(now),
                lastLogin);
    }
}
