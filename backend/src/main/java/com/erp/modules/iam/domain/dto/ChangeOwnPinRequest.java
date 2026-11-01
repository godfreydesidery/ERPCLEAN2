package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A waiter changes their own PIN, proving it with the current PIN or their password. */
public record ChangeOwnPinRequest(
        @NotBlank @Size(max = 200) String currentSecret,
        @NotBlank @Size(max = 10) String newPin) {
}
