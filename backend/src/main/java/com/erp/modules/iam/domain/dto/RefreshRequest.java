package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Exchange a valid refresh token for a new access+refresh pair. */
public record RefreshRequest(@NotBlank String refreshToken) {
}
