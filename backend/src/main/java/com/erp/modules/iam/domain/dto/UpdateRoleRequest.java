package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Update-role request. Only {@code name} and {@code description} are mutable after creation.
 * Code is immutable; system flag cannot be changed via the API (BR-7).
 */
public record UpdateRoleRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 255) String description) {
}
