package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-role request. {@code code} must be org-unique (validated in the service).
 * System roles are seeded via migration and cannot be created through the API.
 */
public record CreateRoleRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 255) String description) {
}
