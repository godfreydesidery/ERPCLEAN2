package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new 4-digit POS PIN, set by a manager. */
public record SetPosPinRequest(@NotBlank @Size(max = 10) String pin) {
}
