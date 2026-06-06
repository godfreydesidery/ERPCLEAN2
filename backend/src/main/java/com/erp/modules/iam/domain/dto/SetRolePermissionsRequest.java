package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Replace-permissions request for a role. An empty list is valid (clears all permissions).
 * Unknown codes are rejected with 409 (service validates against the seeded catalogue).
 */
public record SetRolePermissionsRequest(@NotNull List<String> permissionCodes) {
}
