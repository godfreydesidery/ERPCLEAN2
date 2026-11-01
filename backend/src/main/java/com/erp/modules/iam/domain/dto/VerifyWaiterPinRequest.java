package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Who's serving?" on a shared till, with the waiter's PIN. */
public record VerifyWaiterPinRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 10) String pin) {
}
