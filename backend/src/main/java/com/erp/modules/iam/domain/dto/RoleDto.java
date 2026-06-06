package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.Role;
import java.util.List;

/**
 * Response shape for a role (DATA-MODEL §1.6). Carries both {@code id} and {@code uid}.
 * Permission codes are sorted for deterministic output.
 */
public record RoleDto(
        Long id,
        String uid,
        String code,
        String name,
        String description,
        boolean system,
        String status,
        List<String> permissionCodes) {

    public static RoleDto from(Role r) {
        List<String> codes = r.getPermissions().stream()
                .map(p -> p.getCode())
                .sorted()
                .toList();
        return new RoleDto(
                r.getId(),
                r.getUid(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                r.isSystem(),
                r.getStatus().name(),
                codes);
    }
}
