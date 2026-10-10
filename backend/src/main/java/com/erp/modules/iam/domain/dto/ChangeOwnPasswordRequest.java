package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Self-service password change (ADM-02 / PAR-14). The current password is always required; the
 * new one is checked against the password policy server-side.
 */
public record ChangeOwnPasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(max = 200) String newPassword
) {

    /** Never let the passwords reach a log line through the record's generated toString. */
    @Override
    public String toString() {
        return "ChangeOwnPasswordRequest[***]";
    }
}
