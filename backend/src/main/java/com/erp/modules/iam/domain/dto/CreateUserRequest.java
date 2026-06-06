package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body to create a new non-root application user (DATA-MODEL §1.4). {@code is_root} is
 * bootstrap-only and intentionally absent here — it can never be set via the API.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Size(max = 100) String password,
        @Size(max = 160) @Email String email,
        @Size(max = 40) String phone) {
}
