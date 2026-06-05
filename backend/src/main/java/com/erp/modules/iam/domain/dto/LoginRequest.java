package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Login credentials. Username is matched case-insensitively (stored lowercased). */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
