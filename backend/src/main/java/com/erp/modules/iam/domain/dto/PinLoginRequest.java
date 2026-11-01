package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POS sign-in with a waiter's 4-digit PIN. */
public record PinLoginRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 10) String pin) {
}
