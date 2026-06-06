package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin password-reset request. Validated by {@code PasswordPolicy} before encoding. */
public record SetPasswordRequest(
        @NotBlank @Size(max = 100) String password) {
}
