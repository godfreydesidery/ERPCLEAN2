package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Update mutable branch fields. Default status is changed via the dedicated set-default endpoint,
 * not here, so the "clear old default" invariant lives in one place.
 */
public record UpdateBranchRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 64) String timeZone) {
}
